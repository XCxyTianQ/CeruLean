package io.cerulean.regionizer;

/**
 * 区域 tick 的时间学（纯逻辑，可单测）。
 *
 * <p>这一块是从上游 Folia 的 `Schedule` + {@code TickRegionScheduler} 的语义里提炼出来的，
 * 也是工程研究（study/folia/notes/12 §2.2、§7.1）明确点名的"最容易写错"的两条：</p>
 *
 * <ol>
 *   <li><b>catch-up（补课）</b>：错过 n 个周期就在一次调用里补 n 个 tick
 *       （{@code tickCount = max(1, periodsAhead)}），但<b>区域自己的 tick 计数每次只 +1</b> ⇒
 *       gameTime 与区域 tick 会分叉；因此跨区域迁移绝对 deadline 时必须按 {@code fromTickOffset} 折算；</li>
 *   <li><b>防饿死（deadline 拉平）</b>：下一次起点取 {@code max(tickEnd, 理想下一周期)}——
 *       只做 catch-up 会让慢区域永远插队，只做拉平会让区域永久落后。</li>
 * </ol>
 *
 * <p>单位：纳秒；周期默认 50 ms（20 TPS）。所有比较都用<b>回绕安全</b>的差值符号法，
 * 不用裸 {@code a < b}（纳秒回绕会翻转比较结果）。</p>
 */
public final class TickSchedule {

    /** 一个 tick 的周期（20 TPS）。 */
    public static final long PERIOD_NS = 50_000_000L;

    /** 未设置 deadline 的哨兵（与上游一致：{@code Long.MIN_VALUE}）。 */
    public static final long DEADLINE_NOT_SET = Long.MIN_VALUE;

    private long lastPeriod = DEADLINE_NOT_SET;

    /** 是否已设置起点。 */
    public boolean isSet() {
        return this.lastPeriod != DEADLINE_NOT_SET;
    }

    /** 设置"上一周期起点"（= 首次起点 - 一个周期）。 */
    public void setFirstStart(final long firstStartNs) {
        this.lastPeriod = firstStartNs - PERIOD_NS;
    }

    /** 第 {@code period} 个周期应当开始的时刻。 */
    public long deadline(final long periodNs) {
        return this.lastPeriod + periodNs;
    }

    /** 相对 {@code now} 已经错过了多少个周期（≥0）。 */
    public int periodsAhead(final long periodNs, final long nowNs) {
        if (!this.isSet()) {
            return 0;
        }
        final long diff = nowNs - this.lastPeriod;
        if (diff <= 0L) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, diff / periodNs);
    }

    /** 本次应当补几个 tick（至少 1）。 */
    public int tickCount(final long nowNs) {
        return Math.max(1, this.periodsAhead(PERIOD_NS, nowNs));
    }

    /** 把周期起点推进 {@code count} 个周期。 */
    public void advanceBy(final int count) {
        this.lastPeriod += (long) count * PERIOD_NS;
    }

    /**
     * 一次 tick 结束后的收尾（★ 上游顺序：**先推进 tickCount 个周期，再取 max(tickEnd, 理想起点)**）。
     *
     * <p>把两步合成一个方法是为了**不易误用**：只做 advance 会让慢区域永远插队，
     * 只做 max 会让区域永久落后（工程研究 notes/12 §2.2 的结论）。</p>
     *
     * @param tickCount 本次实际补的周期数（来自 {@link #tickCount(long)}）
     * @param tickEndNs 本次 tick 实际结束时刻
     * @return 下一次应当开始的时刻
     */
    public long completeTick(final int tickCount, final long tickEndNs) {
        this.advanceBy(Math.max(1, tickCount));
        final long ideal = this.deadline(PERIOD_NS);        // 保持相位的理想起点
        final long next = greatest(tickEndNs, ideal);       // 跑超时就不追相位，从实际结束时刻重新起算
        this.lastPeriod = next - PERIOD_NS;
        return next;
    }

    /** 下一次应当开始的时刻（未设置时返回 {@link #DEADLINE_NOT_SET}）。 */
    public long nextStart() {
        return this.isSet() ? this.deadline(PERIOD_NS) : DEADLINE_NOT_SET;
    }

    /**
     * 合并时**继承被吸收区域的时间表**（上游做法）。
     *
     * <p>两个要点：</p>
     * <ol>
     *   <li><b>折算</b>：被吸收区域的绝对 deadline 属于它自己的 tick 基准，必须用
     *       {@code tickOffset = into.tickCount - from.tickCount} 折算到本区域基准
     *       （工程研究 notes/12 §2.4：catch-up 让 gameTime 与区域 tick 分叉，不折算就错）；</li>
     *   <li><b>取较晚者</b>：上游取"较大的时间延迟"以免合并后 TPS 超过 20
     *       （两个区域刚合并时不该比原来更频繁地 tick）。</li>
     * </ol>
     */
    public void adoptLaterOf(final TickSchedule other, final long tickOffsetPeriods) {
        if (other == null || !other.isSet() || !this.isSet()) {
            return;
        }
        final long transferred = transferDeadline(other.deadline(PERIOD_NS), 0L, tickOffsetPeriods);
        final long ours = this.deadline(PERIOD_NS);
        if (greater(transferred, ours)) {
            this.lastPeriod = transferred - PERIOD_NS;      // 采用更晚的相位
        }
    }

    /** 分裂时让新区域**继承源区域的相位**（否则新区域会白等一个周期）。 */
    public void adoptPhaseFrom(final TickSchedule other) {
        if (other == null || !other.isSet()) {
            return;
        }
        this.lastPeriod = other.lastPeriod;
    }

    /** 回绕安全的取大（用差值符号法，避免纳秒回绕时裸比较出错）。 */
    public static long greatest(final long a, final long b) {
        return (b - a) > 0L ? b : a;
    }

    /** 回绕安全的"a 是否晚于 b"。 */
    public static boolean greater(final long a, final long b) {
        return (a - b) > 0L;
    }

    /** 回绕安全的比较：a 是否早于 b。 */
    public static boolean earlierThan(final long a, final long b) {
        return (a - b) < 0L;
    }

    /** 跨区域迁移时折算绝对 deadline：{@code to = from + (toTick - fromTick)}（单位：tick）。 */
    public static long transferDeadline(final long fromDeadlineNs, final long fromTick, final long toTick) {
        return fromDeadlineNs + (toTick - fromTick) * PERIOD_NS;
    }

    @Override
    public String toString() {
        return "TickSchedule{lastPeriod=" + (this.lastPeriod == DEADLINE_NOT_SET ? "未设置" : this.lastPeriod) + '}';
    }
}
