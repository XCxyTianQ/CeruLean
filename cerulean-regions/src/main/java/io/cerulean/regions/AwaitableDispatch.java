package io.cerulean.regions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.papermc.paper.threadedregions.RegionizedServer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 把"触碰某个位置所在区域内容"的工作投递到**该 area 的拥有者区域线程**上（EXP 移植的对齐件）。
 *
 * <p>模板来自上游 Folia 的 {@code SetBlockCommand} / {@code FillCommand}：它们把命令体包进
 * {@code RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(level, cx, cz, …)}，
 * 并**立即返回**一个占位结果（fire-and-forget）。</p>
 *
 * <h2>为什么必须有这个类（实测根因，docs/15）</h2>
 * <p>Folia 的世界访问不只看"区块所有权"，还看**线程有没有正在 tick 那个 level 的区域**：
 * {@code Level.getCurrentWorldData()} 就是 {@code TickRegionScheduler.getCurrentRegionizedWorldData()}，
 * 它</p>
 * <ul>
 *   <li>在**没有任何区域 tick 上下文**的线程上返回 {@code null} ⇒ {@code Level.getBlockState}
 *       在 {@code getCurrentWorldData().captureTreeGeneration} 处 NPE（实测：RCON 命令在
 *       global region tick 线程上跑，clone 就是这样崩的）；</li>
 *   <li>在**别的 world 的区域线程**上抛 {@code IllegalStateException: World mismatch}。</li>
 * </ul>
 * <p>所以"把世界访问挪进拥有者线程"是唯一正确做法。</p>
 *
 * <h2>两个原语，选哪个</h2>
 * <table>
 *   <tr><th></th><th>{@link #dispatch}</th><th>{@link #call}</th></tr>
 *   <tr><td>语义</td><td>投递后**立即返回**（不阻塞）</td><td>投递并**等待结果**</td></tr>
 *   <tr><td>用于</td><td>写路径 / 不需要返回值的命令体（clone、data set、setblock 类）</td><td>命令契约**要求同步返回值**的读取（目前只有 {@code data get}）</td></tr>
 *   <tr><td>代价</td><td>无</td><td>调用方线程会被阻塞（见下）</td></tr>
 * </table>
 *
 * <h2>{@link #call} 的阻塞为什么无法消除（T3 定论，docs/15 §T3）</h2>
 * <p>不是实现偷懒：{@code data get} 的返回值要经
 * {@code DedicatedServer.runCommand} → {@code rconConsoleSource.getCommandResponse()} 同步返回给
 * RCON，而它在读取响应之前只 {@code .join()} 了 global region 那一个任务。命令契约（Brigadier 返回
 * {@code int}）与 RCON 的取值时机都要求"值在命令体返回前就已算出"⇒ **必须有人等**。
 * 若强行 fire-and-forget，{@code data get} 的 RCON 响应会变成空串（实测对照：{@code setblock} 就是空响应）。
 * 要真正去掉这次等待，得改 {@code DedicatedServer.runCommand} 让"命令未完成"这件事对 RCON 可见——
 * 那是另一个量级的改动，不在本案范围。</p>
 * <p>因此 {@link #call} 保留等待，但**加了自检**：计数 + 首次告警（含线程名/位置），
 * 让"谁在阻塞"可观测而不是静默发生。见 {@link #stats()}。</p>
 *
 * <p><b>线程规则</b>（两个原语共用）</p>
 * <ul>
 *   <li>当前线程就是该位置的拥有者（{@code TickThread.isTickThreadFor} 为真）⇒ **内联执行**，零开销、零投递；</li>
 *   <li>非 {@link ServerLevel}（客户端侧）⇒ 无处可投，内联执行；</li>
 *   <li>否则投递给拥有者区域的 tick 队列。</li>
 * </ul>
 */
public final class AwaitableDispatch {

    private static final Logger LOGGER = LoggerFactory.getLogger("CeruLean/AwaitableDispatch");

    /** {@link #call} 等待拥有者的上限。超时即抛，避免"静默失败"。 */
    private static final long WAIT_SECONDS = 5L;

    /**
     * **调用方本身是 tick 线程**时用的等待上限。
     *
     * <p>实测根因（docs/17 §R1 附）：Folia 的 EDF 调度器是**按需**起线程的，实测出现过
     * {@code /cerulean regions} 报"调度线程 1"的情形 —— 此时 global region 与目标 world region
     * **跑在同一个 {@code Folia Region Scheduler Thread #0} 上**。从 global tick 线程投递后再同步等待，
     * 等于等自己把队列排空 ⇒ **自锁**，一直卡到超时，并且会把 global region 卡到触发
     * {@code [FoliaWatchdogThread] Global region has not responded in 5s}。
     *
     * <p>所以 tick 线程调用方的等待上限必须**远小于 watchdog 阈值**：宁可快速失败并给出可诊断的错误，
     * 也不要把整个 global region 拖停。非 tick 线程（控制台/RPC 之类）仍用 {@link #WAIT_SECONDS}。</p>
     */
    private static final long TICK_THREAD_WAIT_MS = 1500L;

    // ── 自检计数（T3 验收用；只读快照见 stats()）────────────────────────
    private static final AtomicLong INLINE = new AtomicLong();
    private static final AtomicLong QUEUED = new AtomicLong();
    private static final AtomicLong CALL_BLOCKING = new AtomicLong();
    private static final AtomicLong CALL_BLOCKING_NANOS = new AtomicLong();
    /** 其中"调用方本身是区域 tick 线程却还要等别人"的次数——这才是真正的隐患。 */
    private static final AtomicLong CALL_BLOCKING_FROM_TICK_THREAD = new AtomicLong();
    /** tick 线程调用方因"等不到目标区域"而快速失败的次数。 */
    private static final AtomicLong FAST_FAILS = new AtomicLong();
    private static volatile String lastBlockingSite = "(none)";
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED = new java.util.concurrent.atomic.AtomicBoolean();

    private AwaitableDispatch() {
    }

    /**
     * 当前线程是否就是该位置所在区域的拥有者 tick 线程。
     *
     * <p>给"内联拿结果 / 投递占位返回"这种分支用：拥有者时可以内联执行并同步拿到返回值，
     * 非拥有者时只能投递（fire-and-forget）。</p>
     */
    public static boolean owns(final Level level, final BlockPos pos) {
        return ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * **非阻塞**投递：需要返回值时不要用它。
     *
     * <p>这是 Folia 上游（{@code SetBlockCommand} / {@code FillCommand}）采用的语义：
     * 投递完立刻返回，命令的结果消息由拥有者线程自己发出去。</p>
     */
    public static void dispatch(final Level level, final BlockPos pos, final Runnable task) {
        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        // 1) 我就是拥有者 ⇒ 内联（既最快，也避开投递队列）
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, chunkX, chunkZ)) {
            INLINE.incrementAndGet();
            task.run();
            return;
        }
        // 2) 非服务端世界（没有区域调度器）⇒ 无处可投，只能就地做
        if (!(level instanceof ServerLevel serverLevel)) {
            INLINE.incrementAndGet();
            task.run();
            return;
        }
        // 3) 投递给拥有该区块的区域线程，**立即返回**
        QUEUED.incrementAndGet();
        RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(serverLevel, chunkX, chunkZ, task);
    }

    /**
     * 投递一个**需要返回结果**的动作并等待它。
     *
     * <p>只有"命令契约要求值在返回前算出"的场景才该用它（当前仅 {@code data get}）；
     * 其余一律用 {@link #dispatch}。等待的不可消除性见类文档。</p>
     */
    public static <T> T call(final Level level, final BlockPos pos, final Supplier<T> task) {
        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        // 1) 我就是拥有者（Folia 的 isTickThreadFor 是区域感知的）⇒ 内联
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, chunkX, chunkZ)) {
            INLINE.incrementAndGet();
            return task.get();
        }
        // 2) 非服务端世界：无处可投，直接做
        if (!(level instanceof ServerLevel serverLevel)) {
            INLINE.incrementAndGet();
            return task.get();
        }
        // 3) 投递给拥有者并等待（自检：这次等待会被记账，必要时告警）
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        QUEUED.incrementAndGet();
        RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(serverLevel, chunkX, chunkZ, () -> {
            try {
                result.set(task.get());
            } catch (final Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        });
        final boolean callerIsTickThread = ca.spottedleaf.moonrise.common.util.TickThread.isTickThread();
        if (callerIsTickThread) {
            CALL_BLOCKING_FROM_TICK_THREAD.incrementAndGet();
        }
        if (WARNED.compareAndSet(false, true)) {
            LOGGER.warn(
                "AwaitableDispatch.call 正在阻塞等待区域线程：线程 '{}'（是区域 tick 线程：{}）等待区块 {}/{}（{}）。"
                    + "这是命令契约要求同步返回值导致的（见 AwaitableDispatch 类文档）；写路径请改用非阻塞的 dispatch()。",
                Thread.currentThread().getName(), callerIsTickThread, chunkX, chunkZ, pos
            );
        }
        final long startNanos = System.nanoTime();
        final long waitMillis = callerIsTickThread ? TICK_THREAD_WAIT_MS : WAIT_SECONDS * 1000L;
        try {
            if (!done.await(waitMillis, TimeUnit.MILLISECONDS)) {
                if (callerIsTickThread) {
                    FAST_FAILS.incrementAndGet();
                }
                throw new IllegalStateException("AwaitableDispatch 等待区域线程超时（" + waitMillis + "ms，"
                        + (callerIsTickThread ? "调用方是 tick 线程 ⇒ 目标区域可能与本线程共用同一个调度线程，"
                            + "同步等待会自锁，故提前失败以免拖停整个区域" : "调用方非 tick 线程")
                        + "）：位置 " + pos + "，区块 " + chunkX + "/" + chunkZ
                        + "。需要同步返回值的读取请改用可异步的路径，或让服务端有 ≥2 个调度线程"
                        + "（/cerulean regions 可看当前线程数）。");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AwaitableDispatch 被中断", interrupted);
        } finally {
            final long waited = System.nanoTime() - startNanos;
            CALL_BLOCKING.incrementAndGet();
            CALL_BLOCKING_NANOS.addAndGet(waited);
            lastBlockingSite = Thread.currentThread().getName() + " @ " + level.dimension().identifier() + " " + pos;
        }
        if (failure.get() != null) {
            throw new IllegalStateException("AwaitableDispatch 任务抛出异常", failure.get());
        }
        return result.get();
    }

    /** 自检快照：供探针/验收断言"写路径不再阻塞"。 */
    public static String stats() {
        final long calls = CALL_BLOCKING.get();
        final long totalMs = CALL_BLOCKING_NANOS.get() / 1_000_000L;
        return "inline=" + INLINE.get()
            + " queued=" + QUEUED.get()
            + " callBlocking=" + calls
            + " callBlockingTotalMs=" + totalMs
            + " callBlockingFromTickThread=" + CALL_BLOCKING_FROM_TICK_THREAD.get()
            + " fastFails=" + FAST_FAILS.get()
            + " lastBlockingSite=" + lastBlockingSite;
    }
}
