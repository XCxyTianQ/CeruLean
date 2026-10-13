package io.cerulean.regions;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import ca.spottedleaf.concurrentutil.scheduler.EDFSchedulerThreadPool;

import io.cerulean.regionizer.Region;
import io.cerulean.regionizer.TickSchedule;

/**
 * EDF 区域 tick 调度器（用并发库里与上游同一套的 {@code EDFSchedulerThreadPool}）。
 *
 * <p><b>刻意保持保守</b>：</p>
 * <ul>
 *   <li>线程是**普通 Thread（守护线程）**，不是 {@code TickThread}——Paper 里
 *       "是 TickThread 就等于主线程"的假设遍布各处（研究 §5.4），先不碰它；</li>
 *   <li>区域线程此刻只跑区域自身的 tick（拓扑 + 计数），**不碰世界内容**（B5 才搬内容）；</li>
 *   <li>线程数默认按上游公式：{@code cores/2 → ≤4 取 1 → 否则再 /4}，本机 20 核 ⇒ 2。</li>
 * </ul>
 *
 * <p><b>锁序</b>：Moonrise ticketLock → Regionizer.lock → （本调度器的内部锁）。
 * 因此<b>绝不允许</b>在持有调度器内部锁时回调区域化器；本类是"区域激活 → 调度"，
 * 方向相反（区域化器锁内 → 调度器锁内），与上游一致。</p>
 */
public final class EdfRegionTickScheduler implements RegionTickScheduler {

    private static final long FIRST_DELAY_NS = TickSchedule.PERIOD_NS;   // 首次起点 = now + 50ms（上游一致）

    private final CeruLeanRegions owner;
    private final EDFSchedulerThreadPool pool;
    private final AtomicInteger registered = new AtomicInteger();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicInteger rejected = new AtomicInteger();

    public EdfRegionTickScheduler(final CeruLeanRegions owner, final int threads) {
        this.owner = owner;
        final AtomicInteger index = new AtomicInteger();
        final ThreadFactory factory = runnable -> {
            final Thread thread = new Thread(runnable, "CeruLean Region Tick #" + index.getAndIncrement());
            thread.setDaemon(true);            // 守护线程：即使关服路径漏掉 shutdown，也不会卡住 JVM 退出
            return thread;
        };
        this.pool = new EDFSchedulerThreadPool(threads, factory);
    }

    @Override
    public String mode() {
        return "edf";
    }

    @Override
    public void start() {
        this.pool.start();
    }

    @Override
    public void driveMainThread() {
        // 区域由池线程自行 tick，主线程无需驱动
    }

    /** 区域激活（在区域化器写锁内被调用）⇒ 造句柄并入队。 */
    public void onRegionActive(final Region<RegionData> region) {
        final RegionTickHandle handle = new RegionTickHandle(this.owner, region,
                System.nanoTime() + FIRST_DELAY_NS);
        final RegionData data = region.data();
        if (data != null) {
            data.schedule.setFirstStart(System.nanoTime() + FIRST_DELAY_NS);
            data.handle = handle;
        }
        this.registered.incrementAndGet();
        this.pool.schedule(handle);
    }

    /** 区域被销毁/合并（在区域化器写锁内被调用）⇒ 让句柄自然退出。 */
    public void onRegionDeactivate(final Region<RegionData> region) {
        final RegionData data = region.data();
        if (data != null && data.handle != null) {
            data.handle.deactivate();
            data.handle = null;
        }
    }

    /** 观测：完成一次区域 tick（由句柄回调）。 */
    public void countCompleted() {
        this.completed.incrementAndGet();
    }

    @Override
    public void haltOnly() {
        this.pool.halt();          // 只置停 + unpark，绝不 join（可能在池线程上调用）
    }

    @Override
    public void shutdown() {
        this.pool.halt();
        if (!this.pool.join(5000L)) {
            io.cerulean.regions.CeruLeanRegions.logWarn("CeruLean 区域线程池未在 5 秒内停止");
        }
    }

    @Override
    public String summary() {
        return String.format("EDF 调度：线程 %d / 注册 %d / 已完成 %d",
                this.pool.getAliveThreads().length, this.registered.get(), this.completed.get());
    }
}
