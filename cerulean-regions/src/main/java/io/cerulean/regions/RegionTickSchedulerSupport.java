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
            // 自检开关（仅在诊断命令武装后消费一次）
            if (CeruLeanRegions.consumeThrowInTick()) {
                throw new IllegalStateException("CeruLean 自检：故意在区域 tick 中抛出异常");
            }
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
        } catch (final Throwable throwable) {
            regionFailed(owner, region, throwable);
            regionizer.markNotTicking(region);      // 尽力释放（失败也不掩盖原始异常）
            return false;                           // 不再排下一轮
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

    /**
     * fail-stop（照上游 {@code TickRegionScheduler.regionFailed} 的语义）：
     * 区域 tick 抛异常时**宁可停服也不带伤跑**。
     *
     * <p>三步：① 记清楚现场（区域 id / 段数 / 维度 / 异常栈）；
     * ② 只 halt 调度器（**不 join**——我们可能正跑在调度线程上）；
     * ③ 把"停服"这个动作**投递到主线程**去做（上游注释：不能在这里同步调用，
     * 因为当前就在调度线程上）。</p>
     */
    static void regionFailed(final CeruLeanRegions owner, final Region<RegionData> region,
                             final Throwable throwable) {
        final long regionId = region == null ? -1L : region.id();
        final int sections = region == null ? -1 : region.sectionCount();
        final String dimension = String.valueOf(owner.level().dimension().identifier());
        CeruLeanRegions.logError(String.format(
                "CeruLean 区域 tick 失败 ⇒ fail-stop（区域 #%d / 段 %d / 维度 %s / 线程 %s）",
                regionId, sections, dimension, Thread.currentThread().getName()), throwable);
        try {
            owner.haltScheduler();
        } catch (final Throwable haltFailure) {
            CeruLeanRegions.logError("CeruLean halt 调度器时再次失败", haltFailure);
        }
        final net.minecraft.server.MinecraftServer server = owner.level().getServer();
        server.execute(() -> {
            CeruLeanRegions.logError("CeruLean fail-stop：区域 tick 失败，正在停服以免带着不一致状态继续运行", throwable);
            server.halt(false);
        });
    }
}
