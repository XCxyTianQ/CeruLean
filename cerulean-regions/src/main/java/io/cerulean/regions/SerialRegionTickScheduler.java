package io.cerulean.regions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.cerulean.regionizer.Region;

/**
 * 串行（确定性）区域 tick 调度器 —— B1 的第一步。
 *
 * <p>主线程每 tick 调用 {@link #driveMainThread()}：按**区域 id 升序**逐个 tick，
 * 顺序固定、完全可复现。它的价值是作为**对拍基线**：与 C2 行为逐项一致，
 * 且与 {@link EdfRegionTickScheduler} 共用同一个 tick 体（{@link RegionTickSchedulerSupport}），
 * 因此"换成真线程池"只应改变时序，不应改变语义。</p>
 */
public final class SerialRegionTickScheduler implements RegionTickScheduler {

    private final CeruLeanRegions owner;
    private long rounds;
    private long ticks;

    public SerialRegionTickScheduler(final CeruLeanRegions owner) {
        this.owner = owner;
    }

    @Override
    public String mode() {
        return "serial";
    }

    @Override
    public void start() {
        // 串行模式无需启动任何东西
    }

    @Override
    public void driveMainThread() {
        final List<Region<RegionData>> snapshot = new ArrayList<>(this.owner.regionizer().regionsSnapshot());
        if (snapshot.isEmpty()) {
            return;
        }
        // 确定性顺序：区域 id 升序（EDF 的平手规则也用 id，便于对照）
        snapshot.sort(Comparator.comparingLong(Region::id));
        final long gameTime = this.owner.level().getServer().getTickCount();
        final long threadId = Thread.currentThread().threadId();
        for (final Region<RegionData> region : snapshot) {
            if (RegionTickSchedulerSupport.tickRegion(this.owner, region, threadId, gameTime)) {
                this.ticks++;
            }
        }
        this.rounds++;
    }

    @Override
    public void shutdown() {
        // 无自有线程
    }

    @Override
    public String summary() {
        return String.format("串行调度：轮 %d / 区域 tick %d 次", this.rounds, this.ticks);
    }
}
