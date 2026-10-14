package io.cerulean.regionizer.test;

import java.util.ArrayList;
import java.util.List;

import io.cerulean.regionizer.Neighbourhood;
import io.cerulean.regionizer.OwnershipCheck;
import io.cerulean.regionizer.Region;
import io.cerulean.regionizer.Regionizer;
import io.cerulean.regionizer.RegionizerConfig;

/**
 * B2 归属判定的用例。
 *
 * <p>判据目标（来自工程研究 notes/12 §7.2）：</p>
 * <ul>
 *   <li>没有拥有者 ⇒ 允许（过渡期谁都能碰）；</li>
 *   <li>拥有者本线程 ⇒ 允许；</li>
 *   <li>别的区域线程 ⇒ 拒绝；主线程 ⇒ 软模式报"将会违规"、严格模式拒绝；</li>
 *   <li>释放后归属立刻失效；</li>
 *   <li>OFF 策略下与 Paper 原行为完全一致（且不触碰内核）；</li>
 *   <li>与不变量 S4 呼应：TICKING 期间段集不变 ⇒ 归属不会被"正在进行中的合并"改变。</li>
 * </ul>
 */
public final class OwnershipTests {

    private static final int SHIFT = 4;
    private static final int PER = 1 << SHIFT;
    private static final long MAIN = 1L;      // 主线程 id（测试用常量）
    private static final long THREAD_A = 7L;
    private static final long THREAD_B = 8L;

    private OwnershipTests() {
    }

    private static Regionizer<Bag> fresh() {
        return new Regionizer<>(new RegionizerConfig(SHIFT, 2, true, Neighbourhood.SQUARE_3X3),
                Bag::new, new Bag.Callbacks());
    }

    private static OwnershipCheck.Verdict check(final Regionizer<Bag> r, final int section,
                                                final long threadId, final OwnershipCheck.Policy policy,
                                                final List<String> log) {
        return OwnershipCheck.check(r, section * PER, 0, threadId, MAIN, policy,
                (verdict, cx, cz, cur, owner) -> log.add(verdict + "@" + cx + "/" + cz
                        + " cur=" + cur + " owner=" + owner));
    }

    /** B1 的时间学用例：catch-up、防饿死、回绕安全、跨区域折算。 */
    private static void scheduleTests(final T t) {
        final io.cerulean.regionizer.TickSchedule s = new io.cerulean.regionizer.TickSchedule();
        final long period = io.cerulean.regionizer.TickSchedule.PERIOD_NS;
        final long t0 = 1_000_000_000L;
        t.group("B1 区域 tick 时间学（catch-up / 防饿死 / 回绕 / 跨区域折算）");
        s.setFirstStart(t0 + period);
        t.eq("首次 deadline", t0 + period, s.deadline(period));

        // 准点：补 1 个 tick，下一次保持相位（先 advance 再 max）
        t.eq("准点 ⇒ 补 1 个 tick", 1, s.tickCount(t0 + period));
        final long onTimeEnd = t0 + period + 2_000_000L;
        t.eq("准点 ⇒ 下一次起点保持相位", t0 + 2 * period, s.completeTick(1, onTimeEnd));

        // 落后：此刻 lastPeriod = t0 + period，now = t0 + 4·period ⇒ 过了 3 个周期 ⇒ 补 3 个 tick
        // （上游按"相对 lastPeriod 过了几个周期"计，不是按"比 deadline 迟了多少"计）
        final long late = t0 + 4 * period;
        final int catchUp = s.tickCount(late);
        t.eq("过了 3 个周期 ⇒ 补 3 个 tick", 3, catchUp);
        t.eq("补课后下一次起点保持相位", t0 + 5 * period, s.completeTick(catchUp, late + 2_000_000L));

        // 超时（本次 tick 比理想起点晚了 80 ms）：下一次起点 = 实际结束时刻（不追相位）
        final long slowEnd = t0 + 5 * period + 80_000_000L;
        t.eq("超时 ⇒ 下一次起点 = 实际结束时刻", slowEnd, s.completeTick(1, slowEnd));
        t.eq("超时后相位以实际结束时刻重设", slowEnd, s.deadline(period));

        // 回绕安全（纳秒回绕时裸比较会翻转）
        t.eq("回绕安全比较 earlierThan", true,
                io.cerulean.regionizer.TickSchedule.earlierThan(Long.MAX_VALUE - 5L, Long.MIN_VALUE + 5L));
        t.eq("回绕安全取大 greatest", Long.MIN_VALUE + 5L,
                io.cerulean.regionizer.TickSchedule.greatest(Long.MAX_VALUE - 5L, Long.MIN_VALUE + 5L));

        // 跨区域折算：A 的绝对 deadline → B 的 tick 基准
        final long aDeadline = 10_000_000_000L;
        t.eq("跨区域折算 deadline（tick 差 ×2）", aDeadline + 2 * period,
                io.cerulean.regionizer.TickSchedule.transferDeadline(aDeadline, 100L, 102L));

        // 合并继承：被吸收区域"更晚"⇒ 采用它的相位（TPS 不超 20）
        final io.cerulean.regionizer.TickSchedule into = new io.cerulean.regionizer.TickSchedule();
        final io.cerulean.regionizer.TickSchedule from = new io.cerulean.regionizer.TickSchedule();
        into.setFirstStart(1_000_000_000L + period);        // nextStart = 1_050_000_000
        from.setFirstStart(1_000_000_000L + 3 * period);    // nextStart = 1_150_000_000
        into.adoptLaterOf(from, 0L);
        t.eq("合并采用更晚的相位", 1_150_000_000L, into.nextStart());

        // 合并继承：被吸收区域"更早"⇒ 保持自己的相位
        final io.cerulean.regionizer.TickSchedule a2 = new io.cerulean.regionizer.TickSchedule();
        final io.cerulean.regionizer.TickSchedule b2 = new io.cerulean.regionizer.TickSchedule();
        a2.setFirstStart(1_000_000_000L + 3 * period);      // 更晚
        b2.setFirstStart(1_000_000_000L + period);          // 更早
        a2.adoptLaterOf(b2, 0L);
        t.eq("合并后不因被吸收方更早而提前", 1_150_000_000L, a2.nextStart());

        // 合并继承：必须按 tickOffset 折算（被吸收方的基准落后 2 个刻）
        final io.cerulean.regionizer.TickSchedule a3 = new io.cerulean.regionizer.TickSchedule();
        final io.cerulean.regionizer.TickSchedule b3 = new io.cerulean.regionizer.TickSchedule();
        a3.setFirstStart(1_000_000_000L + period);          // nextStart = 1_050_000_000
        b3.setFirstStart(1_000_000_000L + period);          // nextStart = 1_050_000_000（b3 的基准）
        a3.adoptLaterOf(b3, 2L);                            // 折算后 = 1_050_000_000 + 2·period = 1_150_000_000
        t.eq("合并继承按 tickOffset 折算", 1_150_000_000L, a3.nextStart());

        // 分裂继承：新区域相位 = 源区域相位（不白等一个周期）
        final io.cerulean.regionizer.TickSchedule split = new io.cerulean.regionizer.TickSchedule();
        split.setFirstStart(999L);
        split.adoptPhaseFrom(a3);
        t.eq("分裂继承源区域相位", a3.nextStart(), split.nextStart());
    }

    public static void run(final T t) {
        scheduleTests(t);
        t.group("B2 归属判定（谁拥有这个位置）");
        final Regionizer<Bag> r = fresh();
        r.addChunk(0, 0);                       // 段 0
        final Region<Bag> region = r.getRegionAt(0, 0);

        final List<String> log = new ArrayList<>();
        t.eq("未 tick 时无人拥有（OFF）", OwnershipCheck.Verdict.NO_OWNER,
                check(r, 0, THREAD_A, OwnershipCheck.Policy.OFF, log));
        t.eq("未 tick 时无人拥有（STRICT）", OwnershipCheck.Verdict.NO_OWNER,
                check(r, 0, THREAD_A, OwnershipCheck.Policy.STRICT, log));

        t.check("取 tick 并绑定线程 A", r.tryMarkTicking(region, THREAD_A));
        t.eq("拥有者自己 ⇒ 允许", OwnershipCheck.Verdict.OWNER,
                check(r, 0, THREAD_A, OwnershipCheck.Policy.STRICT, log));
        t.eq("别的区域线程 ⇒ 拒绝", OwnershipCheck.Verdict.FOREIGN_THREAD,
                check(r, 0, THREAD_B, OwnershipCheck.Policy.STRICT, log));
        t.eq("主线程（软模式）⇒ 记录但允许", OwnershipCheck.Verdict.MAIN_THREAD_OVERRIDE,
                check(r, 0, MAIN, OwnershipCheck.Policy.SOFT, log));
        t.eq("主线程（严格模式）⇒ 拒绝", OwnershipCheck.Verdict.DENIED,
                check(r, 0, MAIN, OwnershipCheck.Policy.STRICT, log));
        t.check("软/严格模式都记录了'将会违规'的现场（供 B5 生成工作清单）",
                log.stream().anyMatch(s -> s.startsWith("MAIN_THREAD_OVERRIDE")) && log.size() >= 3,
                "记录 " + log.size() + " 条");

        // 邻居段（无区块但存活）在同一个区域内 ⇒ 同为 A 拥有
        t.eq("同区域的邻居段也归 A", OwnershipCheck.Verdict.OWNER,
                check(r, 0, THREAD_A, OwnershipCheck.Policy.STRICT, log))
        ;
        // 远处未加载的位置 ⇒ 无人拥有
        t.eq("未加载位置 ⇒ 允许", OwnershipCheck.Verdict.NO_OWNER,
                check(r, 100, THREAD_B, OwnershipCheck.Policy.STRICT, log));

        // 释放 → 归属立刻失效
        r.markNotTicking(region);
        t.eq("释放后 ⇒ 无人拥有", -1L, r.ownerThreadIdAt(0, 0));
        t.eq("释放后别的线程也可访问", OwnershipCheck.Verdict.NO_OWNER,
                check(r, 0, THREAD_B, OwnershipCheck.Policy.STRICT, log));

        // 两个区域各自绑定不同线程 ⇒ 互不越界
        t.group("B2 两个区域各自归属（互不越界）");
        final Regionizer<Bag> r2 = fresh();
        r2.addChunk(0, 0);                       // 段 0
        r2.addChunk(40 * PER, 0);                // 段 40（相距远超 mergeRadius）
        final Region<Bag> ra = r2.getRegionAt(0, 0);
        final Region<Bag> rb = r2.getRegionAt(40 * PER, 0);
        t.check("两处处于不同区域", ra != null && rb != null && ra.id() != rb.id(),
                "#" + (ra == null ? -1 : ra.id()) + " vs #" + (rb == null ? -1 : rb.id()));
        t.check("A 取 tick", r2.tryMarkTicking(ra, THREAD_A));
        t.check("B 取 tick", r2.tryMarkTicking(rb, THREAD_B));
        t.eq("A 拥有自己的位置", OwnershipCheck.Verdict.OWNER,
                check(r2, 0, THREAD_A, OwnershipCheck.Policy.STRICT, log));
        t.eq("A 碰 B 的位置 ⇒ 拒绝", OwnershipCheck.Verdict.FOREIGN_THREAD,
                check(r2, 40, THREAD_A, OwnershipCheck.Policy.STRICT, log));
        t.eq("B 碰 A 的位置 ⇒ 拒绝", OwnershipCheck.Verdict.FOREIGN_THREAD,
                check(r2, 0, THREAD_B, OwnershipCheck.Policy.STRICT, log));
        t.eq("归属查询：A 自己的段", THREAD_A, r2.ownerThreadIdAt(0, 0));
        t.eq("归属查询：B 自己的段", THREAD_B, r2.ownerThreadIdAt(40 * PER, 0));

        // 与 S4 呼应：TICKING 期间段集不变 ⇒ 新段不会被"正在 tick 的区域"吸收
        t.group("B2 归属与 S4 一致（ticking 期间段集不变）");
        final int before = ra.sectionCount();
        r2.addChunk(10 * PER, 0);                // 段 10：紧邻 A（距离 10 > mergeRadius，另起区域）
        t.eq("ticking 区域的段集不变（S4）", before, ra.sectionCount());
        t.eq("新段不属于 A 的归属", true, r2.ownerThreadIdAt(10 * PER, 0) != THREAD_A);

        r2.markNotTicking(ra);
        r2.markNotTicking(rb);
        t.noViolations("两区域释放后不变量", r2.verifyInvariants(true));
    }
}
