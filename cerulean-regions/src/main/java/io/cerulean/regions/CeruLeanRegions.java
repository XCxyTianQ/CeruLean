package io.cerulean.regions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.cerulean.regionizer.Region;
import io.cerulean.regionizer.Regionizer;
import io.cerulean.regionizer.RegionizerCallbacks;
import io.cerulean.regionizer.RegionizerConfig;

import net.minecraft.server.level.ServerLevel;

/**
 * 每 world 一份的区域化接线（C2：只驱动拓扑，不引入并发）。
 *
 * <p>接线方式照上游（见 study/folia/notes/11 的施工图）：</p>
 * <ul>
 *   <li>{@code BaseChunkSystemHooks.onChunkHolderCreate/Delete} → {@link #addChunk}/{@link #removeChunk}；</li>
 *   <li>{@code ServerLevel} 持有本对象；</li>
 *   <li>每 tick 一次<b>串行拓扑 tick</b>（{@link #tickTopology()}）：让每个区域经历一次"取 tick → 释放"，
 *       即 C1 的稳定化（合并 → 回收死段 → 必要时分裂）。</li>
 * </ul>
 *
 * <p><b>线程</b>：{@code addChunk/removeChunk} 由 Moonrise 区块系统线程调用，{@code tickTopology} 由主线程调用；
 * 因此这是 C1 内核第一次在真实并发下被使用（内核本身用 StampedLock 保护）。</p>
 */
public final class CeruLeanRegions {

    /**
     * 服务端配置：16×16 区块的段（= 上游 gridExponent 4）、**3×3 方形邻居**（= 上游
     * {@code emptySectionCreateRadius=1}）、**合并半径 2 段 = 32 区块**（上游 {@code regionSectionMergeRadius=1}，我们更保守）。
     *
     * <p>取值来自上游 Folia 26.2 补丁里 {@code ServerLevel} 的构造实参；C3 会把它做成服务端可配置项。</p>
     */
    public static final RegionizerConfig CONFIG = RegionizerConfig.defaults();

    private final ServerLevel level;
    private final Regionizer<RegionData> regionizer;
    private final Counters counters = new Counters();

    public CeruLeanRegions(final ServerLevel level) {
        this.level = level;
        this.regionizer = new Regionizer<>(CONFIG, RegionData::new, this.counters);
    }

    public ServerLevel level() {
        return this.level;
    }

    public Regionizer<RegionData> regionizer() {
        return this.regionizer;
    }

    public Counters counters() {
        return this.counters;
    }

    /** 区块 holder 建立 → 拓扑加段（由区块系统线程调用）。 */
    public void addChunk(final int chunkX, final int chunkZ) {
        this.regionizer.addChunk(chunkX, chunkZ);
    }

    /** 区块 holder 删除 → 拓扑减段（由区块系统线程调用）。 */
    public void removeChunk(final int chunkX, final int chunkZ) {
        this.regionizer.removeChunk(chunkX, chunkZ);
    }

    /**
     * 串行拓扑 tick：让每个区域各经历一次"取 tick → 释放"。
     *
     * <p>C2 阶段世界内容仍由 Paper 原样 tick；这里只驱动拓扑本身，因此<b>不改变任何原版行为</b>。</p>
     */
    public void tickTopology() {
        final List<Region<RegionData>> snapshot = this.regionizer.regionsSnapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        final long gameTime = this.level.getServer().getTickCount();
        for (final Region<RegionData> region : snapshot) {
            if (!this.regionizer.tryMarkTicking(region)) {
                continue;                       // 已被并发吞并/销毁，或正忙
            }
            final RegionData data = region.data();
            if (data != null) {
                if (data.firstTickGameTime < 0L) {
                    data.firstTickGameTime = gameTime;
                }
                data.ticks++;
            }
            this.regionizer.markNotTicking(region);
        }
    }

    /** 供诊断命令与日志使用的一行摘要。 */
    public String summary() {
        final List<Region<RegionData>> regions = this.regionizer.regionsSnapshot();
        int maxSections = 0;
        long maxRegionId = -1L;
        for (final Region<RegionData> region : regions) {
            if (region.sectionCount() > maxSections) {
                maxSections = region.sectionCount();
                maxRegionId = region.id();
            }
        }
        return String.format("%s: 区域 %d / 段 %d（存活 %d）/ 区块 %d / 最大区域 #%d(%d 段)",
                this.level.dimension().identifier(), regions.size(), this.regionizer.sectionCount(),
                this.regionizer.aliveSectionCount(), this.regionizer.chunkCount(), maxRegionId, maxSections);
    }

    /** 供诊断命令使用：区域明细（按段数降序，最多 limit 条）。 */
    public List<String> detail(final int limit) {
        final List<Region<RegionData>> regions = new ArrayList<>(this.regionizer.regionsSnapshot());
        regions.sort(Comparator.comparingInt(Region<RegionData>::sectionCount).reversed()
                .thenComparingLong(Region::id));
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < regions.size() && i < limit; i++) {
            final Region<RegionData> region = regions.get(i);
            final RegionData data = region.data();
            out.add(String.format("  #%d %s 段 %d / 区块 %d / 拓扑 tick %d / 首次 tick 于服务端第 %d 刻",
                    region.id(), region.state(), region.sectionCount(), region.chunkCount(),
                    data == null ? -1L : data.ticks, data == null ? -1L : data.firstTickGameTime));
        }
        return out;
    }

    public String countersLine() {
        return String.format("  回调计数: 创建 %d / 激活 %d / 去激活 %d / 销毁 %d / 合并 %d / 分裂 %d",
                this.counters.created, this.counters.active, this.counters.deactivated,
                this.counters.destroyed, this.counters.preMerges, this.counters.splits);
    }

    /** 区域生命周期回调：只做计数，不触碰世界状态（区块系统线程会调用）。 */
    public static final class Counters implements RegionizerCallbacks<RegionData> {

        public volatile long created;
        public volatile long active;
        public volatile long deactivated;
        public volatile long destroyed;
        public volatile long preMerges;
        public volatile long preSplits;
        public volatile long splits;

        @Override
        public void onRegionCreate(final Region<RegionData> region) {
            this.created++;
        }

        @Override
        public void onRegionActive(final Region<RegionData> region) {
            this.active++;
        }

        @Override
        public void onRegionDeactivate(final Region<RegionData> region) {
            this.deactivated++;
        }

        @Override
        public void onRegionDestroy(final Region<RegionData> region) {
            this.destroyed++;
        }

        @Override
        public void onRegionPreMerge(final Region<RegionData> from, final Region<RegionData> into) {
            this.preMerges++;
            if (from.data() != null) {
                from.data().mergedAway++;
            }
        }

        @Override
        public void onRegionPreSplit(final Region<RegionData> from) {
            this.preSplits++;
        }

        @Override
        public void onRegionSplit(final Region<RegionData> from, final Region<RegionData> newRegion) {
            this.splits++;
        }

        @Override
        public void mergeState(final RegionData from, final RegionData into, final long tickOffset) {
            // C2：区域状态只有计数，合并时累加即可（tickOffset 的用法在 C3 的调度状态上验证）
            into.ticks += from.ticks;
            into.mergedAway += from.mergedAway;
            if (into.firstTickGameTime < 0L) {
                into.firstTickGameTime = from.firstTickGameTime;
            }
        }
    }
}
