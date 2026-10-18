package io.cerulean.regions;


import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegions;

import net.kyori.adventure.text.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * CeruLean 在 **Folia 基座**上的诊断命令（收缩后的全部内容）。
 *
 * <p><b>为什么收缩到只剩它</b>：Folia 26.3 的基座已经提供了区域化线程的全部实现
 * （{@code ThreadedRegionizer} / {@code TickRegionScheduler} / region-aware {@code TickThread} /
 * {@code scheduleChunkTaskEventually} / 由区域 tick 驱动票据处理），我们此前自研的
 * 调度骨架（B1）、归属闸门（B2）、MC 侧接线（C2）都已被上游取代并删除。
 * 本模块只保留**上游没有的、面向运维的只读诊断**：一条命令看区域拓扑与调度概况。</p>
 *
 * <p>只使用上游**公开** API：{@code ServerLevel.regioniser}（public 字段）、
 * {@code ThreadedRegionizer.computeForAllRegions/computeForRegions}、
 * {@code TickRegionScheduler.getTotalThreadCount()}、{@code RegionizedServer.getCurrentTick()}。</p>
 */
public final class CeruLeanCommand {

    public static final String DESCRIPTION = "CeruLean 区域诊断（Folia 基座）";

    /** 命令权限（Bukkit 权限名，注册进 Paper 的权限体系）。 */
    public static final String PERMISSION = "cerulean.command";

    private CeruLeanCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> create() {
        return Commands.literal("cerulean")
            .requires(source -> source.getSender().hasPermission(PERMISSION))
            .then(Commands.literal("regions")
                .executes(CeruLeanCommand::listRegions))
            .then(Commands.literal("regionat")
                .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                    .then(Commands.argument("chunkZ", IntegerArgumentType.integer())
                        .executes(CeruLeanCommand::regionAt))))
            .then(Commands.literal("dispatch")
                .executes(CeruLeanCommand::dispatchStats))
            .then(Commands.literal("exp")
                .executes(CeruLeanCommand::expStats))
            .build();
    }

    /**
     * {@code /cerulean dispatch}：投递原语的自检快照（T3 验收用）。
     *
     * <p>要看的是 {@code callBlocking}——那是唯一还会阻塞调用方的地方，且只应该由
     * {@code data get} 这类"命令契约要求同步返回值"的读取产生；写路径走
     * {@code dispatch()}，投递即返回，不该在这里涨。</p>
     */
    /**
     * {@code /cerulean exp}：EXP 链 walker 的自检快照。
     *
     * <p>挂起/恢复计数是"链真的被接上了"的唯一可观测证据：只有命令侧调用了
     * {@code ExpChainSupport.registerDeferred}（SetBlockCommand 的 CeruLean 分支），
     * walker 才会攒出待派发批次、在批边界挂起并恢复。</p>
     */
    private static int expStats(final CommandContext<CommandSourceStack> context) {
        final StringBuilder out = new StringBuilder("CeruLean EXP 链自检");
        out.append('\n').append("  上下文活跃 ").append(com.azurebranches.command.ExpChainSupport.isContextActive());
        out.append(" / 模式 ").append(com.azurebranches.command.CommandBlockSupport.mode());
        out.append('\n').append("  登记：内联 ").append(com.azurebranches.command.ExpChainSupport.inlineRegisteredCount())
            .append(" / 跨区域 ").append(com.azurebranches.command.ExpChainSupport.deferredRegisteredCount());
        out.append('\n').append("  挂起 ").append(com.azurebranches.command.ExpChainSupport.suspendedCount())
            .append(" / 恢复 ").append(com.azurebranches.command.ExpChainSupport.resumedCount())
            .append(" / 超时 ").append(com.azurebranches.command.ExpChainSupport.timeoutCount())
            .append(" / 被取代 ").append(com.azurebranches.command.ExpChainSupport.supersededCount());
        out.append('\n').append("  批上限 ").append(com.azurebranches.command.ExpChainSupport.maxBatchSize())
            .append(" / 远程超时 ").append(com.azurebranches.command.ExpChainSupport.remoteTimeoutMs()).append("ms");
        out.append('\n').append("  命令方块失败收容 ").append(com.azurebranches.command.CommandBlockSupport.containedFailureCount());
        // OCC（EXP3+）：读集校验与回滚重试的实况 —— 这是"OCC 真的在工作"的唯一可观测面
        out.append('\n').append("  OCC 校验：通过 ").append(com.azurebranches.command.ExpChainSupport.validationPassedCount())
            .append(" / 重试 ").append(com.azurebranches.command.ExpChainSupport.validationRetryCount())
            .append(" / 耗尽 ").append(com.azurebranches.command.ExpChainSupport.validationExhaustedCount())
            .append(" / 冲突率 ").append(String.format(java.util.Locale.ROOT, "%.2f",
                com.azurebranches.command.ExpChainSupport.validationConflictRatio()));
        // OCC 开关实况：PhaseValidator.describe() 直接读 command_blocks.exp.validation.*，
        // 这是"配置真的接上了"的运行时证据（enabled/disabled 一眼可见）。
        out.append('\n').append("  ").append(com.azurebranches.command.PhaseValidator.describe());
        // 数据面拦截计数：证明 Level/Scoreboard 的钩子确实被走到（否则读集恒空）
        out.append('\n').append("  数据面：方块读 ").append(com.azurebranches.command.ExpChainSupport.dataInterceptBlockReadCount())
            .append(" / 计分读 ").append(com.azurebranches.command.ExpChainSupport.scoreInterceptReadCount())
            .append(" / 计分写 ").append(com.azurebranches.command.ExpChainSupport.scoreInterceptWriteCount())
            .append(" / 计分缓存命中 ").append(com.azurebranches.command.ExpChainSupport.scoreInterceptCacheHitCount())
            .append(" / 读集记录 ").append(com.azurebranches.command.ExpChainSupport.blockReadRecordedCount())
            .append(" / NBT写拦截 ").append(com.azurebranches.command.ExpChainSupport.nbtInterceptWriteCount())
            .append(" / NBT缓存命中 ").append(com.azurebranches.command.ExpChainSupport.nbtCacheHitCount());
        out.append('\n').append("  补偿：计分 ").append(com.azurebranches.command.ExpChainSupport.scoreCompensationCount())
            .append(" / 失败 ").append(com.azurebranches.command.ExpChainSupport.scoreCompensationFailureCount())
            .append(" / NBT ").append(com.azurebranches.command.ExpChainSupport.nbtCompensationCount())
            .append(" / 竞争 ").append(com.azurebranches.command.ExpChainSupport.scoreCompensationRaceCount());
        context.getSource().getSender().sendMessage(Component.text(out.toString()));
        return 1;
    }

    private static int dispatchStats(final CommandContext<CommandSourceStack> context) {
        context.getSource().getSender().sendMessage(Component.text("CeruLean 投递自检\n  " + AwaitableDispatch.stats()));
        return 1;
    }

    /** {@code /cerulean regions}：调度线程数 + 每 world 的区域数与段参数。 */
    private static int listRegions(final CommandContext<CommandSourceStack> context) {
        final StringBuilder out = new StringBuilder("CeruLean 区域诊断（Folia 基座）");
        out.append('\n').append("  调度线程 ")
            .append(TickRegions.getScheduler() == null ? "未启动" : TickRegions.getScheduler().getTotalThreadCount())
            .append(" / 当前 tick ").append(currentTickSafely());
        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            final int[] regions = {0};
            level.regioniser.computeForAllRegions(region -> regions[0]++);
            out.append('\n').append("  ").append(level.dimension().identifier())
                .append(": 区域 ").append(regions[0])
                .append(" / 段 ").append(level.regioniser.regionSectionChunkSize).append(" 区块")
                .append(" / 合并半径 ").append(level.regioniser.regionSectionMergeRadius).append(" 段")
                .append(" / 物化半径 ").append(level.regioniser.emptySectionCreateRadius);
        }
        context.getSource().getSender().sendMessage(Component.text(out.toString()));
        return 1;
    }

    /** {@code /cerulean regionat <chunkX> <chunkZ>}：该区块落在哪个区域（可能多个/无）。 */
    private static int regionAt(final CommandContext<CommandSourceStack> context) {
        final int chunkX = IntegerArgumentType.getInteger(context, "chunkX");
        final int chunkZ = IntegerArgumentType.getInteger(context, "chunkZ");
        final ServerLevel level = MinecraftServer.getServer().overworld();
        // 注意：ThreadedRegion.id 是**包内可见**（Folia 自己的代码同包才能读），
        // 因此这里只报告"覆盖该区块的区域个数"，不暴露内部 id。
        final int[] covering = {0};
        level.regioniser.computeForRegions(chunkX, chunkZ, chunkX, chunkZ, region -> covering[0]++);
        final String text = "区块 (" + chunkX + "," + chunkZ + ") → "
                + (covering[0] == 0 ? "无区域" : "被 " + covering[0] + " 个区域覆盖");
        context.getSource().getSender().sendMessage(Component.text(text));
        return 1;
    }

    /** {@code RegionizedServer.getCurrentTick()} 声明抛异常（非区域 tick 线程时），这里降级为"未知"。 */
    private static String currentTickSafely() {
        try {
            return String.valueOf(RegionizedServer.getCurrentTick());
        } catch (final Throwable ignored) {
            return "未知（当前非区域 tick 线程）";
        }
    }
}
