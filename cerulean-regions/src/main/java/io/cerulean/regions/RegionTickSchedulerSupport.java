package io.cerulean.regions;

import io.cerulean.regionizer.Region;

/**
 * 区域 tick 体：两种调度器共用，保证"串行模式"与"EDF 模式"的**语义完全一致**，
 * 唯一差别是"谁来调用、按什么顺序调用"。
 */
final class RegionTickSchedulerSupport {

    private RegionTickSchedulerSupport() {
    }

    /**
     * 跑一个区域的一次 tick。
     *
     * @param ownerThreadId 本次 tick 的拥有者线程 id（写入归属，B2 的闸门读它）
     * @return true 表示区域仍然可调度（READY）；false 表示它已死/被合并/需重新注册
     */
    static boolean tickRegion(final CeruLeanRegions owner, final Region<RegionData> region,
                              final long ownerThreadId, final long gameTime) {
        final var regionizer = owner.regionizer();
        if (!regionizer.tryMarkTicking(region, ownerThreadId)) {
            return false;                       // 已被并发吞并/销毁，或正忙
        }
        final long tickStart = System.nanoTime();
        try {
            final RegionData data = region.data();
            if (data != null) {
                final int tickCount = data.schedule.tickCount(tickStart);
                if (tickCount > 1) {
                    data.catchUpTicks += tickCount - 1;
                }
                data.ticks += tickCount;
                data.lastTickCount = tickCount;
                if (data.firstTickGameTime < 0L) {
                    data.firstTickGameTime = gameTime;
                }
                // C2/C3 现阶段：世界内容仍由 Paper 原样 tick，这里只驱动区域自身（拓扑 + 状态）。
                // B5 会把"内容 tick"搬到这里，届时按 tickCount 循环。
            }
        } finally {
            // markNotTicking 返回"该区域是否仍然存活"（false = 已被合并进别的区域，调用方应丢弃引用）
            final boolean survives = regionizer.markNotTicking(region);
            final RegionData data = region.data();
            if (data != null) {
                final long tickEnd = System.nanoTime();
                data.lastTickNanos = tickEnd - tickStart;
                data.totalTickNanos += data.lastTickNanos;
                data.schedule.completeTick(Math.max(1, data.lastTickCount), tickEnd);
            }
            return survives;
        }
    }
}
