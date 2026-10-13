package io.cerulean.regions;

import ca.spottedleaf.concurrentutil.scheduler.SchedulableTick;

import io.cerulean.regionizer.Region;

/**
 * EDF 模式下"一个区域一份"的调度句柄。
 *
 * <p>关键契约（来自工程研究 notes/12 §2.2）：<b>句柄不可复用</b>——
 * 一旦从调度器摘除就必须换新的（上游用 {@code copy()} 重造并继承 tick 计数与 deadline）。
 * 这里用一个 {@code active} 标志替代上游的 copy 链：重新注册时**新造一个句柄**。</p>
 */
public final class RegionTickHandle extends SchedulableTick {

    private final CeruLeanRegions owner;
    private final Region<RegionData> region;
    private volatile boolean active = true;

    public RegionTickHandle(final CeruLeanRegions owner, final Region<RegionData> region, final long firstStart) {
        this.owner = owner;
        this.region = region;
        this.setScheduledStart(firstStart);
    }

    /** 让该句柄自然退出（不再被调度）。 */
    public void deactivate() {
        this.active = false;
    }

    public Region<RegionData> region() {
        return this.region;
    }

    @Override
    public boolean runTick() {
        if (!this.active) {
            return false;
        }
        final long gameTime = this.owner.level().getServer().getTickCount();
        final boolean stillReady = RegionTickSchedulerSupport.tickRegion(
                this.owner, this.region, Thread.currentThread().threadId(), gameTime);
        if (!stillReady || !this.active) {
            // 区域已死/被合并/被取消 ⇒ 不再排下一轮（新区域会拿到新句柄）
            return false;
        }
        final RegionData data = this.region.data();
        if (data != null) {
            this.setScheduledStart(data.schedule.deadline(io.cerulean.regionizer.TickSchedule.PERIOD_NS));
        }
        if (this.owner.scheduler() instanceof EdfRegionTickScheduler edf) {
            edf.countCompleted();
        }
        return true;
    }

    @Override
    public boolean hasTasks() {
        return false;                          // EDF 下中间任务不生效（上游注释 "Not implemented"）
    }

    @Override
    public boolean runTasks(final java.util.function.BooleanSupplier canContinue) {
        return true;
    }
}
