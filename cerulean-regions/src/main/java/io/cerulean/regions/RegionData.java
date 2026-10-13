package io.cerulean.regions;

import io.cerulean.regionizer.TickSchedule;

/**
 * 区域自有状态（C2 起为最小集，B1 起承担调度状态）。
 *
 * <p>注意它由区域化器在<b>区块系统线程</b>上创建，因此构造过程<b>不得触碰世界状态</b>
 * （见 docs/06 §7 的回调契约）；需要世界信息的字段一律在拓扑 tick（主线程/区域线程）里惰性初始化。</p>
 */
public final class RegionData {

    /** 该区域经历过的 tick 次数（含 catch-up 补的刻）。 */
    public long ticks;

    /** 该区域作为合并源被并入他者的次数。 */
    public long mergedAway;

    /** 该区域首次被 tick 时的世界游戏刻；-1 表示尚未 tick 过。 */
    public long firstTickGameTime = -1L;

    // ── B1：调度状态（时间学见 TickSchedule 与其 10 项单测） ──────────────────

    /** 本区域的 tick 时间表（周期起点、catch-up、防饿死）。 */
    public final TickSchedule schedule = new TickSchedule();

    /** 因落后而补的额外刻数（catch-up 计数，用于观测）。 */
    public long catchUpTicks;

    /** 本次 tick 实际补的周期数。 */
    public int lastTickCount = 1;

    /** 最近一次 tick 的耗时与累计耗时（纳秒）。 */
    public long lastTickNanos;
    public long totalTickNanos;

    /** EDF 模式下的调度句柄（串行模式为 null）。注意：句柄**不可复用**（上游契约）。 */
    public volatile RegionTickHandle handle;
}
