package io.cerulean.regions;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;

import io.cerulean.regionizer.Region;

import net.kyori.adventure.text.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 诊断命令 {@code /cerulean}：查看区域拓扑。
 *
 * <p><b>注册方式照上游</b>：与 Paper 自己的 {@code /version}、{@code /plugins} 一样，
 * 走 Paper 的命令 API（{@code PaperCommands.registerCommands()} 里注册），而<b>不是</b>直接往
 * vanilla 调度器根节点加命令。原因：Paper 有个测试
 * （{@code MinecraftCommandPermissionsTest}）校验"调度器根命令 ↔ minecraft.command.* 权限"的
 * 一一对应，往根节点加命令会让它失败——上游自己的内部命令都因此走 API 注册。</p>
 */
public final class CeruLeanCommand {

    public static final String DESCRIPTION = "CeruLean 区域拓扑诊断（/cerulean regions / regionat）";

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
            .build();
    }

    private static int listRegions(final CommandContext<CommandSourceStack> context) {
        final io.cerulean.regionizer.RegionizerConfig cfg = CeruLeanRegions.config();
        final StringBuilder out = new StringBuilder("CeruLean 区域拓扑")
            .append("（段位移 ").append(cfg.regionShift)
            .append(" / 段=").append(1 << cfg.regionShift).append(" 区块")
            .append(" / 合并半径 ").append(cfg.mergeRadius).append(" 段")
            .append(" / 邻居 ").append(cfg.neighbourhood).append('）');
        for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
            out.append('\n').append("  ").append(level.ceruleanRegions.summary());
            for (final String line : level.ceruleanRegions.detail(5)) {
                out.append('\n').append("  ").append(line);
            }
            out.append('\n').append(level.ceruleanRegions.countersLine());
        }
        context.getSource().getSender().sendMessage(Component.text(out.toString()));
        return Command.SINGLE_SUCCESS;
    }

    private static int regionAt(final CommandContext<CommandSourceStack> context) {
        final int chunkX = IntegerArgumentType.getInteger(context, "chunkX");
        final int chunkZ = IntegerArgumentType.getInteger(context, "chunkZ");
        final ServerLevel level = context.getSource().getLocation().getWorld() == null
            ? MinecraftServer.getServer().overworld()
            : ((org.bukkit.craftbukkit.CraftWorld) context.getSource().getLocation().getWorld()).getHandle();
        final Region<RegionData> region = level.ceruleanRegions.regionizer().getRegionAt(chunkX, chunkZ);
        final String text = region == null
            ? String.format("区块 (%d,%d) 不属于任何区域（段已消亡或未加载）", chunkX, chunkZ)
            : String.format("区块 (%d,%d) → 区域 #%d（%s，段 %d / 区块 %d）",
                chunkX, chunkZ, region.id(), region.state(), region.sectionCount(), region.chunkCount());
        context.getSource().getSender().sendMessage(Component.text(text));
        return Command.SINGLE_SUCCESS;
    }
}
