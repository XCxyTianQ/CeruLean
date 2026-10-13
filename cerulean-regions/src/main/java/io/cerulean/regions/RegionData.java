package io.cerulean.regions;

/**
 * C2 的最小"区域自有状态"。
 *
 * <p>刻意保持极小：C2 只验证拓扑接线与可观测性；真正的区域状态（调度、OCC 等）在 C3 挂上来。
 * 注意它由区域化器在<b>区块系统线程</b>上创建，因此构造过程<b>不得触碰世界状态</b>
 * （见 docs/06 §7 的回调契约）；需要世界信息的字段一律在拓扑 tick（主线程）里惰性初始化。</p>
 */
public final class RegionData {

    /** 该区域经历过的拓扑 tick 次数（由主线程在释放点递增）。用于证明串行 tick 真的覆盖到每个区域。 */
    public long ticks;

    /** 该区域作为合并源被并入他者的次数。 */
    public long mergedAway;

    /** 该区域首次被拓扑 tick 时的世界游戏刻；-1 表示尚未 tick 过。 */
    public long firstTickGameTime = -1L;
}
