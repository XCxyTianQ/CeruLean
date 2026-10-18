/*
 * CeruLean - 原版技术模式（[compat] mc_technical_mode）
 *
 * 参考实现：Leaves 的 org.leavesmc.leaves.util.McTechnicalModeHelper。
 * 作用是把 Paper 默认关闭的若干"原版技术机制"开关一次性打开，其中
 * allowPistonDuplication 是 TNT 复制（以及地毯/铁轨复制）的前置条件。
 *
 * 时序：必须在 DedicatedServer 的 initializeGlobalConfiguration(...) 之后调用——
 * 该调用之前 GlobalConfiguration.get() 里的实例还没有被赋值（Paper 在此时才创建
 * 并读入 config/paper-global.yml）；AzureBranchesConfig 则更早在 MinecraftServer
 * 的构造里初始化（补丁 0018），所以这里可以安全地用 getOrNull() 读开关。
 *
 * 默认关闭：配置缺省 false ⇒ 本类完全不碰 GlobalConfiguration，行为与现状一致。
 */
package io.cerulean.regions;

import com.azurebranches.config.AzureBranchesConfig;
import io.papermc.paper.configuration.GlobalConfiguration;

/**
 * 原版技术模式：把 Paper 的 unsupportedSettings 中的技术向开关置 true。
 *
 * <p>与 Leaves 的 {@code McTechnicalModeHelper#doMcTechnicalMode()} 对齐的 5 项：</p>
 * <ul>
 *   <li>{@code allowPistonDuplication}：TNT / 地毯 / 铁轨复制（核心项）；</li>
 *   <li>{@code allowHeadlessPistons}：无头活塞；</li>
 *   <li>{@code allowPermanentBlockBreakExploits}：永久方块（基岩、末地门等）可破坏；</li>
 *   <li>{@code allowUnsafeEndPortalTeleportation}：不安全的末地门传送（沙漏复制等）；</li>
 *   <li>{@code skipTripwireHookPlacementValidation}：绊线钩放置校验（绊线复制）。</li>
 * </ul>
 *
 * <p>Leaves 还会放宽 packetLimiter / itemValidation / scoreboards 与实体最大箭矢
 * 无敌时间，那些属于反作弊与包限速范畴，与 TNT 复制无直接关系，本开关暂不改动，
 * 以保持"默认行为与现状完全一致"这一验收前提。</p>
 *
 * <p><b>异常必须吞掉</b>：本方法在服务端启动主流程里调用，任何反射/配置异常都不该让
 * 服务端起不来，因此 catch Throwable 后只打警告。</p>
 */
public final class TechnicalMode {

    private TechnicalMode() {
    }

    /**
     * 若 {@code [compat] mc_technical_mode = true}，打开原版技术机制开关并打一行日志；
     * 否则（或配置尚未初始化时）什么都不做。
     */
    public static void applyIfEnabled() {
        try {
            final AzureBranchesConfig config = AzureBranchesConfig.getOrNull();
            if (config == null) {
                return; // 理论上不会发生：MinecraftServer 构造里已 init
            }
            if (!config.mcTechnicalMode()) {
                return;
            }
            final GlobalConfiguration.UnsupportedSettings unsupported =
                GlobalConfiguration.get().unsupportedSettings;
            unsupported.allowPistonDuplication = true;
            unsupported.allowHeadlessPistons = true;
            unsupported.allowPermanentBlockBreakExploits = true;
            unsupported.allowUnsafeEndPortalTeleportation = true;
            unsupported.skipTripwireHookPlacementValidation = true;
            System.out.println("[CeruLean] mc_technical_mode=on:"
                + " allowPistonDuplication=" + unsupported.allowPistonDuplication
                + " allowHeadlessPistons=" + unsupported.allowHeadlessPistons
                + " allowPermanentBlockBreakExploits=" + unsupported.allowPermanentBlockBreakExploits
                + " allowUnsafeEndPortalTeleportation=" + unsupported.allowUnsafeEndPortalTeleportation
                + " skipTripwireHookPlacementValidation=" + unsupported.skipTripwireHookPlacementValidation);
        } catch (Throwable t) {
            System.err.println("[CeruLean] WARN: mc_technical_mode apply failed (server continues): " + t);
            t.printStackTrace();
        }
    }
}
