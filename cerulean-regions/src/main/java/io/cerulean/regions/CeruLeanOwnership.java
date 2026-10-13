package io.cerulean.regions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.cerulean.regionizer.OwnershipCheck;
import io.cerulean.regionizer.Regionizer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 归属校验的服务端薄适配（B2）。
 *
 * <p>职责只有三件：把 {@code Level} 换成区域化器、把当前线程 id 与主线程 id 传下去、
 * 把"将会违规"的现场**限流采样**后记录下来（供 B5 生成工作清单）。判定逻辑全在纯 Java 的
 * {@link OwnershipCheck}（已被 23 项单测覆盖）。</p>
 *
 * <p><b>热路径纪律</b>：默认 {@code OFF} 时只做一次 volatile 读就返回，<b>不碰内核、不分配</b>；
 * 开启后每次查询也只做一次读锁下的段表查询（无分配），因此可以安全地挂在
 * {@code TickThread.isTickThreadFor} 这种高频入口上。</p>
 */
public final class CeruLeanOwnership {

    /** 现场采样上限：超过后只计数，不再抓栈（避免诊断本身拖慢服务端）。 */
    private static final int MAX_SITES = 200;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static volatile OwnershipCheck.Policy policy = OwnershipCheck.Policy.OFF;
    private static volatile long mainThreadId = -1L;

    private static final AtomicLong violations = new AtomicLong();
    private static final AtomicLong suppressed = new AtomicLong();
    /** 现场签名 → 出现次数（用于汇总"哪些调用点将会违规"）。 */
    private static final Map<String, AtomicLong> SITES = new ConcurrentHashMap<>();

    private CeruLeanOwnership() {
    }

    public static OwnershipCheck.Policy policy() {
        return policy;
    }

    public static long violations() {
        return violations.get();
    }

    public static long suppressed() {
        return suppressed.get();
    }

    public static Map<String, AtomicLong> sites() {
        return SITES;
    }

    /** 由配置加载调用（在主线程上，因此顺手记下主线程 id）。 */
    public static void init(final OwnershipCheck.Policy newPolicy) {
        mainThreadId = Thread.currentThread().threadId();
        policy = newPolicy;
        violations.set(0L);
        suppressed.set(0L);
        SITES.clear();
        if (newPolicy != OwnershipCheck.Policy.OFF) {
            LOGGER.info("CeruLean 归属校验已开启：{}（主线程 id {}）", newPolicy, mainThreadId);
        }
    }

    /**
     * 判定当前线程能否访问该区块位置。
     *
     * <p>{@code OFF}（默认）时恒为 true ⇒ 与 Paper 原行为**完全一致**。</p>
     */
    public static boolean allows(final Level level, final int chunkX, final int chunkZ) {
        final OwnershipCheck.Policy current = policy;
        if (current == OwnershipCheck.Policy.OFF) {
            return true;                                  // 热路径：一次 volatile 读
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;                                  // 非服务端世界不参与归属
        }
        final CeruLeanRegions regions = serverLevel.ceruleanRegions;
        if (regions == null) {
            return true;
        }
        final Regionizer<RegionData> regionizer = regions.regionizer();
        final OwnershipCheck.Verdict verdict = OwnershipCheck.check(regionizer, chunkX, chunkZ,
                Thread.currentThread().threadId(), mainThreadId, current, LISTENER);
        return OwnershipCheck.allows(verdict);
    }

    private static final OwnershipCheck.Listener LISTENER =
            (verdict, chunkX, chunkZ, currentThreadId, ownerThreadId) -> {
                violations.incrementAndGet();
                final String site = currentSite();
                AtomicLong counter = SITES.get(site);
                if (counter == null) {
                    if (SITES.size() >= MAX_SITES) {
                        suppressed.incrementAndGet();
                        return;
                    }
                    counter = SITES.computeIfAbsent(site, key -> {
                        LOGGER.warn("CeruLean 归属：{} 于 {}（位置 {}/{}，当前线程 {}，拥有者 {}）",
                                verdict, key, chunkX, chunkZ, currentThreadId, ownerThreadId);
                        return new AtomicLong();
                    });
                }
                counter.incrementAndGet();
            };

    /** 现场签名 = 第一个非本包/非反射的栈帧（抓栈只在首次见到该现场时发生）。 */
    private static String currentSite() {
        final StackTraceElement[] stack = new Throwable().getStackTrace();
        for (final StackTraceElement element : stack) {
            final String cls = element.getClassName();
            if (cls.startsWith("io.cerulean.") || cls.startsWith("java.") || cls.startsWith("jdk.")) {
                continue;
            }
            return cls + "." + element.getMethodName() + ":" + element.getLineNumber();
        }
        return "<unknown>";
    }

    /**
     * 端到端自检（诊断命令用）：把指定位置的区域临时绑定到一个**假线程 id**，
     * 然后让当前线程走一遍真正的闸门 {@code TickThread.ensureTickThread(...)}，
     * 看策略是否按预期放行/拒绝。这是"补丁真的接上了、策略真的生效"的决定性证据。
     *
     * @return 人类可读的结论（并保证恢复现场：无论结果如何都释放该区域）
     */
    public static String verify(final ServerLevel level, final int chunkX, final int chunkZ) {
        final io.cerulean.regionizer.Regionizer<RegionData> regionizer = level.ceruleanRegions.regionizer();
        final io.cerulean.regionizer.Region<RegionData> region = regionizer.getRegionAt(chunkX, chunkZ);
        if (region == null) {
            return "该位置没有区域（先 forceload 一个区块）";
        }
        final long fakeOwner = 999_999_999L;
        if (!regionizer.tryMarkTicking(region, fakeOwner)) {
            return "该区域当前无法取 tick（正忙或被取消），请稍后重试";
        }
        try {
            // 此刻：区域 TICKING，拥有者 = 假线程；当前线程（命令线程）不是拥有者。
            final boolean allowedByLayer = allows(level, chunkX, chunkZ);
            String thrown = null;
            try {
                ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(level, chunkX, chunkZ,
                        "cerulean ownership verify");
            } catch (final IllegalStateException ex) {
                thrown = ex.getMessage();
            }
            return String.format("策略=%s / 归属层=%s / ensureTickThread=%s",
                    policy, allowedByLayer ? "允许" : "拒绝",
                    thrown == null ? "未抛异常" : ("抛出 IllegalStateException: " + thrown));
        } finally {
            regionizer.markNotTicking(region);
        }
    }

    /** 供诊断命令使用的一行摘要。 */
    public static String summary() {
        if (policy == OwnershipCheck.Policy.OFF) {
            return "归属校验: off（与 Paper 行为一致；开启方式见 cerulean-regions.ownership-check）";
        }
        final StringBuilder out = new StringBuilder("归属校验: ").append(policy)
                .append(" / 将会违规 ").append(violations.get()).append(" 次 / 不同现场 ")
                .append(SITES.size()).append(" 个");
        if (suppressed.get() > 0L) {
            out.append(" / 已省略 ").append(suppressed.get()).append(" 次");
        }
        return out.toString();
    }
}
