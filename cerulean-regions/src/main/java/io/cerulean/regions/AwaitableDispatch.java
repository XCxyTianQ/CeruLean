package io.cerulean.regions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.papermc.paper.threadedregions.RegionizedServer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 把"触碰某个位置内容"的工作投递到**拥有该位置的区域线程**上（EXP 移植的对齐件）。
 *
 * <p>模板来自上游 Folia 的 {@code SetBlockCommand}（它把命令体包进
 * {@code RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(level, cx, cz, …)}）。
 * 区别在于：{@code data get} 这类命令**需要返回值**，所以这里提供"投递并等待结果"的
 * {@link #call}；生产级的等待语义应当换成 AzureBranches 的 continuation 机制
 * （{@code com.azurebranches.command.ExpChainSupport}，已入库），本类用于**对齐验证**。</p>
 *
 * <p><b>线程规则</b></p>
 * <ul>
 *   <li>当前线程就是该位置的拥有者（{@code TickThread.isTickThreadFor} 为真）⇒ **内联执行**，零开销；</li>
 *   <li>否则投递到拥有者的 tick 队列并等待结果（默认 5 秒超时，超时抛异常而不是静默出错）。</li>
 * </ul>
 *
 * <p><b>已知限制（写进 docs/13）</b>：若调用方本身是别的区域的 tick 线程，等待会短暂阻塞那条区域线程；
 * 对齐阶段接受这一点，正式实现改用 continuation（不阻塞）。</p>
 */
public final class AwaitableDispatch {

    /** 等待拥有者执行的上限。超时即抛，避免"静默失败"。 */
    private static final long WAIT_SECONDS = 5L;

    private AwaitableDispatch() {
    }

    /** 投递一个不返回结果的动作。 */
    public static void run(final Level level, final BlockPos pos, final Runnable task) {
        call(level, pos, () -> {
            task.run();
            return Boolean.TRUE;
        });
    }

    /** 投递一个需要返回结果的动作。 */
    public static <T> T call(final Level level, final BlockPos pos, final Supplier<T> task) {
        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        // 1) 我就是拥有者（Folia 的 isTickThreadFor 是区域感知的）⇒ 内联
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, chunkX, chunkZ)) {
            return task.get();
        }
        // 2) 否则投递给拥有者：home region thread 会执行它
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        if (!(level instanceof ServerLevel serverLevel)) {
            return task.get();                       // 非服务端世界：无处可投，直接做
        }
        RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(serverLevel, chunkX, chunkZ, () -> {
            try {
                result.set(task.get());
            } catch (final Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("AwaitableDispatch 等待区域线程超时（"
                        + WAIT_SECONDS + "s）：位置 " + pos + "，区块 " + chunkX + "/" + chunkZ);
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AwaitableDispatch 被中断", interrupted);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("AwaitableDispatch 任务抛出异常", failure.get());
        }
        return result.get();
    }
}
