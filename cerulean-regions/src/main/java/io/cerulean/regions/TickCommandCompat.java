package io.cerulean.regions;

/**
 * {@code /tick} 命令开关（docs/23 §4 P4）。
 *
 * <p>Folia 为了区域安全把 {@code /tick} 的注册注释掉了（{@code Commands.java} 里的
 * {@code //TickCommand.register(...)}），导致逐 tick 观测（freeze / step / query）
 * 在 CeruLean 上完全不可用。本类提供"是否启用"的单一判定点，供 5 处移植点调用：</p>
 *
 * <ul>
 *   <li>{@code io.papermc.paper.threadedregions.RegionizedServer}：globalTick 里的
 *       {@code tickRateManager().tick()}、{@code reduceSprintTicks()+endTickWork()}、
 *       {@code tickTime()} 的 {@code runsNormally()} 门控；</li>
 *   <li>{@code io.papermc.paper.threadedregions.TickRegionScheduler}：每 tick 的
 *       {@code tickCount} 计算（sprint / rate 分支）与 {@code endTickWork()}；</li>
 *   <li>{@code net.minecraft.commands.Commands}：{@code TickCommand.register(...)} 的条件注册；</li>
 *   <li>{@code net.minecraft.server.ServerTickRateManager}：{@code reduceSprintTicks()}；</li>
 *   <li>{@code net.minecraft.server.commands.TickCommand}：{@code MAX_TICKRATE} 公开 + 注释掉 sprint 子命令。</li>
 * </ul>
 *
 * <p><b>为什么用系统属性而不是配置文件</b>：Lophine 的参考实现（补丁 0047）读它自己的 config
 * （{@code GeneralCompatConfig.commandTick}），而 config 的加载时序早于 / 晚于类初始化都不可控，
 * 会出现"打开了但类已经按 false 初始化"的问题。系统属性在 JVM 启动时即已就绪，
 * 且本模块（cerulean-regions 是 srcDir，{@code applyAllPatches} 不会重置）不参与 config 加载。</p>
 *
 * <p><b>默认关闭</b>：不设属性时行为与未移植前完全一致（{@code /tick} 仍是
 * {@code Unknown or incomplete command}）。</p>
 *
 * <p>用法：{@code JAVA_OPTS=-Dcerulean.tickcommand=true tools/smoke/server-up.sh <jar> <dir>}。</p>
 */
public final class TickCommandCompat {

    /** 开关属性名：{@code true}/{@code 1}（大小写不敏感）为开，其余（含未设置）为关。 */
    public static final String PROPERTY = "cerulean.tickcommand";

    /**
     * 只求值一次。
     *
     * <p>注意这里**故意**不是编译期常量：若写成 {@code private static final boolean ENABLED = false;}，
     * javac 会把 {@code enabled()} 的调用点在编译期内联成常量，之后改属性也不会生效。</p>
     */
    private static final boolean ENABLED = resolve();

    private TickCommandCompat() {
    }

    private static boolean resolve() {
        final String raw = System.getProperty(PROPERTY);
        if (raw == null) {
            return false;
        }
        final String value = raw.trim();
        return value.equalsIgnoreCase("true") || value.equals("1");
    }

    /**
     * @return 是否启用 {@code /tick} 命令与其 tick 速率驱动逻辑
     */
    public static boolean enabled() {
        return ENABLED;
    }
}
