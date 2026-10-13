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

    /** 有效参数（由 {@code config/paper-global.yml} 的 cerulean-regions 节驱动；见 {@link #init}）。 */
    private static volatile RegionizerConfig config = RegionizerConfig.defaults();

    public static RegionizerConfig config() {
        return config;
    }

    /**
     * 由配置加载流程调用（{@code CeruLeanGlobalConfig} 的 {@code @PostProcess}）。
     * 做校验/夹取并打印生效值——配置错在这里就应该看得见，而不是等运行期行为诡异。
     */
    public static void init(final CeruLeanGlobalConfig cfg) {
        final int shift = Math.max(0, Math.min(16, cfg.gridExponent));
        final int mergeRadius = Math.max(1, Math.min(8, cfg.mergeRadius));
        io.cerulean.regionizer.Neighbourhood nb;
        try {
            nb = io.cerulean.regionizer.Neighbourhood.valueOf(cfg.neighbourhood.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            nb = io.cerulean.regionizer.Neighbourhood.SQUARE_3X3;
            LOGGER.warn("未知的 cerulean-regions.neighbourhood='{}'，回退为 {}",
                    cfg.neighbourhood, nb);
        }
        if (shift != cfg.gridExponent || mergeRadius != cfg.mergeRadius) {
            LOGGER.warn("cerulean-regions 参数超出允许范围，已夹取：gridExponent {}→{}，mergeRadius {}→{}",
                    cfg.gridExponent, shift, cfg.mergeRadius, mergeRadius);
        }
        config = new RegionizerConfig(shift, mergeRadius, cfg.trackTickingSections, nb);

        io.cerulean.regionizer.OwnershipCheck.Policy ownership =
                io.cerulean.regionizer.OwnershipCheck.Policy.OFF;
        try {
            ownership = io.cerulean.regionizer.OwnershipCheck.Policy.valueOf(
                    cfg.ownershipCheck.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            LOGGER.warn("未知的 cerulean-regions.ownership-check='{}'，回退为 off", cfg.ownershipCheck);
        }
        CeruLeanOwnership.init(ownership);
        SCHEDULER_MODE = "edf".equalsIgnoreCase(cfg.scheduler.trim()) ? "edf" : "serial";
        SCHEDULER_THREADS = cfg.threads;
        LOGGER.info("CeruLean 调度模式：{}（线程数 {}）", SCHEDULER_MODE,
                "edf".equals(SCHEDULER_MODE) ? String.valueOf(schedulerThreads()) : "串行/主线程");
        LOGGER.info("CeruLean 区域化参数生效：{}（段 {} 区块；邻居 {} 个）",
                config, 1 << shift, nb.count());
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    static void logWarn(final String message) {
        LOGGER.warn(message);
    }

    /** 调度模式（配置项 cerulean-regions.scheduler；默认 serial）。 */
    public static String schedulerMode() {
        return SCHEDULER_MODE;
    }

    /** 区域 tick 线程数（EDF 模式用；-1/0 表示按上游公式自动）。 */
    public static int schedulerThreads() {
        final int configured = SCHEDULER_THREADS;
        if (configured > 0) {
            return configured;
        }
        // 上游公式：cores/2；若 ≤4 则取 1，否则再 /4（工程研究 notes/12 §2.1）
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        threads = threads <= 4 ? 1 : threads / 4;
        return Math.max(1, threads);
    }

    private static volatile String SCHEDULER_MODE = "serial";
    private static volatile int SCHEDULER_THREADS = -1;

    private final ServerLevel level;
    private final Regionizer<RegionData> regionizer;
    private final Counters counters = new Counters();
    /** B1：区域调度器（serial = 确定性对拍基线；edf = 用并发库的区域线程池）。 */
    private final RegionTickScheduler scheduler;

    public CeruLeanRegions(final ServerLevel level) {
        this.level = level;
        this.regionizer = new Regionizer<>(config(), RegionData::new, this.counters);
        this.counters.owner = this;
        this.scheduler = "edf".equals(schedulerMode())
                ? new EdfRegionTickScheduler(this, schedulerThreads())
                : new SerialRegionTickScheduler(this);
        this.scheduler.start();
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
        this.scheduler.driveMainThread();
    }

    /** 关服/世界卸载时停止调度（EDF 会 halt + join 线程池）。 */
    public void shutdown() {
        this.scheduler.shutdown();
    }

    public RegionTickScheduler scheduler() {
        return this.scheduler;
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

        /** 反向引用：回调里需要把"区域激活/销毁"通知给调度器。构造后由 CeruLeanRegions 设置。 */
        private CeruLeanRegions owner;

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
            // B1：EDF 模式下区域激活即注册调度（上游同做法）。注意我们此刻在区域化器**写锁内**，
            // 因此这里只允许做"入队"这种不回调区域化器的动作（锁序：Regionizer.lock → 调度器锁）。
            final CeruLeanRegions owner = this.owner;
            if (owner != null && owner.scheduler() instanceof EdfRegionTickScheduler edf) {
                edf.onRegionActive(region);
            }
        }

        @Override
        public void onRegionDeactivate(final Region<RegionData> region) {
            this.deactivated++;
            final CeruLeanRegions owner = this.owner;
            if (owner != null && owner.scheduler() instanceof EdfRegionTickScheduler edf) {
                edf.onRegionDeactivate(region);
            }
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
