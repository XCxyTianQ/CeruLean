package io.cerulean.regions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import io.cerulean.regionizer.Region;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** 诊断命令：{@code /cerulean regions}、{@code /cerulean regionat <x> <z>}。 */
public final class CeruLeanCommand {

    private CeruLeanCommand() {
    }

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("cerulean")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("regions")
                    .executes(context -> listRegions(context.getSource())))
                .then(Commands.literal("regionat")
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(context -> regionAt(context.getSource(),
                                    IntegerArgumentType.getInteger(context, "x"),
                                    IntegerArgumentType.getInteger(context, "z"))))))
        );
    }

    private static int listRegions(final CommandSourceStack source) {
        final StringBuilder out = new StringBuilder("CeruLean 区域拓扑");
        for (final ServerLevel level : source.getServer().getAllLevels()) {
            out.append('\n').append("  ").append(level.ceruleanRegions.summary());
            for (final String line : level.ceruleanRegions.detail(5)) {
                out.append('\n').append("  ").append(line);
            }
            out.append('\n').append(level.ceruleanRegions.countersLine());
        }
        source.sendSuccess(() -> Component.literal(out.toString()), false);
        return 1;
    }

    private static int regionAt(final CommandSourceStack source, final int chunkX, final int chunkZ) {
        final ServerLevel level = source.getLevel();
        final Region<RegionData> region = level.ceruleanRegions.regionizer().getRegionAt(chunkX, chunkZ);
        final String text = region == null
                ? String.format("区块 (%d,%d) 不属于任何区域（段已消亡或未加载）", chunkX, chunkZ)
                : String.format("区块 (%d,%d) → 区域 #%d（%s，段 %d / 区块 %d）",
                        chunkX, chunkZ, region.id(), region.state(), region.sectionCount(), region.chunkCount());
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
