package io.cerulean.regionizer.test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.cerulean.regionizer.Region;
import io.cerulean.regionizer.RegionState;
import io.cerulean.regionizer.Regionizer;
import io.cerulean.regionizer.RegionizerConfig;
import io.cerulean.regionizer.SectionKey;

/**
 * 定向用例：不变量、语义 S1–S4、以及状态迁移契约。
 *
 * <p>语义要点（S1 闭式定义）：存活段 = 有区块的段 ∪ 这些段的四邻。
 * 因此"1 个区块"会产生 5 个存活段；两段相距 2 时中间的四邻段会充当桥接段。</p>
 */
public final class InvariantTests {

    static final int SHIFT = 4;
    static final int PER = 1 << SHIFT;

    private InvariantTests() {
    }

    /** 段坐标 → 该段内的一个区块坐标。 */
    static int cx(final int section, final int offset) {
        return section * PER + offset;
    }

    /** 设"段(sx,sz)里放一个区块"的两个区块坐标。 */
    static int[] at(final int sx, final int sz) {
        return new int[]{cx(sx, 3), cx(sz, 3)};
    }

    static void put(final Regionizer<Bag> r, final int sx, final int sz) {
        final int[] c = at(sx, sz);
        r.addChunk(c[0], c[1]);
    }

    static void drop(final Regionizer<Bag> r, final int sx, final int sz) {
        final int[] c = at(sx, sz);
        r.removeChunk(c[0], c[1]);
    }

    static Set<Long> keys(final int[]... sections) {
        final Set<Long> out = new HashSet<>();
        for (final int[] s : sections) {
            out.add(SectionKey.pack(s[0], s[1]));
        }
        return out;
    }

    /**
     * 定向用例的期望值是按 **4 正邻**手算的，因此这里**显式声明**语义，
     * 不依赖"默认值是什么"（默认值改成上游方形后，用例仍然测它自己要测的东西）。
     */
    static Regionizer<Bag> fresh(final int shift, final int mergeRadius, final Bag.Callbacks cb) {
        return new Regionizer<>(new RegionizerConfig(shift, mergeRadius, true,
                io.cerulean.regionizer.Neighbourhood.CARDINAL_4), Bag::new, cb);
    }

    /** 钉住"默认配置 = 上游语义"这一决策，避免以后被无声改掉。 */
    public static void defaultConfig(final T t) {
        t.group("默认配置 = 上游语义（3×3 方形 + mergeRadius 2 段）");
        final RegionizerConfig def = RegionizerConfig.defaults();
        t.eq("默认邻居语义", io.cerulean.regionizer.Neighbourhood.SQUARE_3X3, def.neighbourhood);
        t.eq("默认合并半径（段）", 2, def.mergeRadius);
        t.eq("默认段大小位移", 4, def.regionShift);
        t.eq("默认关闭 I2 快照（生产路径）", false, def.trackTickingSections);

        final Regionizer<Bag> r = new Regionizer<>(def, Bag::new, new Bag.Callbacks());
        r.addChunk(cx(0, 3), cx(0, 3));
        t.eq("1 个区块 ⇒ 9 个存活段（3×3 方形 + 自身）", 9, r.sectionCount());
        t.noViolations("不变量", r.verifyInvariants(true));
    }

    public static void sectionKey(final T t) {
        t.group("SectionKey 坐标换算");
        for (final int[] p : new int[][]{{0, 0}, {1, -1}, {-1, 1}, {123, -456}, {-1, -1}}) {
            final long k = SectionKey.pack(p[0], p[1]);
            t.check("pack/unpack 往返 " + p[0] + "," + p[1],
                    SectionKey.unpackX(k) == p[0] && SectionKey.unpackZ(k) == p[1]);
        }
        t.eq("chunkToSection(-1, shift=4)", -1, SectionKey.chunkToSection(-1, 4));
        t.eq("chunkToSection(-16, shift=4)", -1, SectionKey.chunkToSection(-16, 4));
        t.eq("chunkToSection(-17, shift=4)", -2, SectionKey.chunkToSection(-17, 4));
        t.eq("sectionToChunk(-2, shift=4)", -32, SectionKey.sectionToChunk(-2, 4));
        t.eq("Chebyshev 距离", 3, SectionKey.sectionDistance(0, 0, 3, -1));
        t.check("pack 不同段不碰撞", SectionKey.pack(1, 2) != SectionKey.pack(2, 1));
    }

    /** S1：闭式存活集合（有区块的段 ∪ 其四邻）与 S3：removeChunk 不改拓扑。 */
    public static void closedFormAlive(final T t) {
        t.group("S1 闭式存活 / S3 removeChunk 不改拓扑 / 死段回收");
        final Bag.Callbacks cb = new Bag.Callbacks();
        final Regionizer<Bag> r = fresh(SHIFT, 2, cb);

        put(r, 0, 0);
        t.eq("1 个区块 ⇒ 1 个区域", 1, r.regionCount());
        t.eq("1 个区块 ⇒ 5 个存活段（自身 + 四邻）", 5, r.sectionCount());
        t.eq("区块数", 1, r.chunkCount());
        t.eq("区域状态 READY", RegionState.READY, r.getRegionAt(cx(0, 3), cx(0, 3)).state());
        t.noViolations("不变量", r.verifyInvariants(true));

        put(r, 0, 1);   // 已有段，不加新段
        t.eq("相邻段复用同一区域", 1, r.regionCount());
        t.eq("段数 8（两段及其四邻的并集）", 8, r.sectionCount());
        t.eq("区块数 2", 2, r.chunkCount());

        // S3：removeChunk 绝不改拓扑
        final Region<Bag> before = r.getRegionAt(cx(0, 3), cx(0, 3));
        final long idBefore = before.id();
        final int sectionsBefore = before.sectionCount();
        drop(r, 0, 1);
        t.eq("S3：区域 id 不变", idBefore, r.getRegionAt(cx(0, 3), cx(0, 3)).id());
        t.eq("S3：段集不变", sectionsBefore, r.getRegionAt(cx(0, 3), cx(0, 3)).sectionCount());
        t.eq("区块数减到 1", 1, r.chunkCount());
        Harness.settle(r);
        t.eq("稳定后段(0,1)仍存活（四邻有区块）", 5, r.sectionCount());
        t.eq("存活段数", 5, r.aliveSectionCount());
        t.noViolations("不变量（稳定态）", r.verifyInvariants(true));

        // 闭式集合精确性：段(0,0) 的四邻 = {(0,1),(0,-1),(1,0),(-1,0)}
        t.eq("存活段集合 = 有区块的段 ∪ 其四邻",
                keys(new int[]{0, 0}, new int[]{0, 1}, new int[]{0, -1}, new int[]{1, 0}, new int[]{-1, 0}),
                r.getRegionAt(cx(0, 3), cx(0, 3)).sectionKeys());

        // 清空 ⇒ 闭式集合为空 ⇒ 全部回收
        drop(r, 0, 0);
        Harness.settle(r);
        t.eq("全部清空后区域数 0", 0, r.regionCount());
        t.eq("全部清空后段数 0", 0, r.sectionCount());
        t.eq("全部清空后区块数 0", 0, r.chunkCount());
        t.check("区域销毁回调被调用", cb.destroys >= 1, "destroys=" + cb.destroys);
        t.check("区域去激活回调被调用", cb.deactivates >= 1, "deactivates=" + cb.deactivates);
        t.noViolations("不变量（稳定态）", r.verifyInvariants(true));
    }

    /** 桥接段：相距 2 的两个区块段通过中间的四邻段连通。 */
    public static void bridgeSection(final T t) {
        t.group("桥接段：相距 2 的段经四邻段连通 / 断开后按闭式回收");
        final Regionizer<Bag> r = fresh(SHIFT, 2, new Bag.Callbacks());
        put(r, 0, 0);
        put(r, 0, 2);
        t.eq("相距 2 的两段同属一区", 1, r.regionCount());
        t.eq("存活段数 9（两段四邻的并集）", 9, r.sectionCount());
        drop(r, 0, 2);
        Harness.settle(r);
        t.eq("段(0,2)及其孤立四邻被回收", 5, r.sectionCount());
        t.eq("存活集合回到单段闭式", keys(new int[]{0, 0}, new int[]{0, 1}, new int[]{0, -1},
                new int[]{1, 0}, new int[]{-1, 0}), r.getRegionAt(cx(0, 3), cx(0, 3)).sectionKeys());
        t.noViolations("不变量（稳定态）", r.verifyInvariants(true));
    }

    /** 分裂：mergeRadius=1 时，中间段消亡 ⇒ 两级断开。 */
    public static void split(final T t) {
        t.group("分裂：mergeRadius=1，中间段消亡导致断开");
        final Bag.Callbacks cb = new Bag.Callbacks();
        final Regionizer<Bag> r = fresh(SHIFT, 1, cb);
        put(r, 0, 0);
        put(r, 0, 2);
        put(r, 0, 4);
        t.eq("链式三段合成一区", 1, r.regionCount());
        t.eq("段数 13", 13, r.regionsSnapshot().get(0).sectionCount());

        drop(r, 0, 2);                       // 掐断中间段
        Harness.settle(r);
        t.eq("分裂为两区", 2, r.regionCount());
        t.eq("分裂前回调 1 次", 1, cb.preSplits);
        t.eq("新区域创建回调 1 次", 1, cb.splits);
        final Set<Set<Long>> want = new HashSet<>(List.of(
                keys(new int[]{0, 0}, new int[]{0, 1}, new int[]{-1, 0}, new int[]{1, 0}, new int[]{0, -1}),
                keys(new int[]{0, 3}, new int[]{0, 4}, new int[]{0, 5}, new int[]{1, 4}, new int[]{-1, 4})));
        t.eq("分裂结果划分正确", want, Harness.partitionOf(r));
        t.noViolations("不变量（稳定态）", r.verifyInvariants(true));

        put(r, 0, 2);                        // 补回中间段 ⇒ 重新连通
        Harness.settle(r);
        t.eq("补回后合并为一区", 1, r.regionCount());
        t.eq("段数回到 13", 13, r.regionsSnapshot().get(0).sectionCount());
        t.noViolations("不变量（稳定态）", r.verifyInvariants(true));
    }

    /** 合并：桥接段触发合并，并换算 tick 偏移。 */
    public static void merge(final T t) {
        t.group("合并：桥接段触发 + tick 偏移换算");
        final Bag.Callbacks cb = new Bag.Callbacks();
        final Regionizer<Bag> r = fresh(SHIFT, 1, cb);
        put(r, 0, 0);
        put(r, 0, 4);
        t.eq("mergeRadius=1 时距离 4 各自成区", 2, r.regionCount());
        final Region<Bag> a = r.getRegionAt(cx(0, 3), cx(0, 3));
        final Region<Bag> b = r.getRegionAt(cx(0, 3), cx(4, 3));
        t.check("两个不同区域", a != b);
        a.setTickCount(10L);
        b.setTickCount(4L);
        Bag.lastMergeTickOffset = Long.MIN_VALUE;

        put(r, 0, 2);                        // 距两者均为 1 ⇒ 立即收敛
        t.eq("桥接后合并为一区", 1, r.regionCount());
        t.eq("段数 13", 13, r.regionsSnapshot().get(0).sectionCount());
        t.check("合并前回调被调用", cb.preMerges >= 1, "preMerges=" + cb.preMerges);
        t.eq("合并 tick 偏移 = |10-4|", 6L, Math.abs(Bag.lastMergeTickOffset));
        t.eq("被吸收区域的数据已清空", null, cb.destroyed.get(0).data());
        t.noViolations("不变量（稳定态）", r.verifyInvariants(true));
    }

    /** TICKING 不可变（I2 / S4）与释放点收敛。 */
    public static void ticking(final T t) {
        t.group("TICKING 不可变（I2 / S4）与释放点收敛");
        final Bag.Callbacks cb = new Bag.Callbacks();
        final Regionizer<Bag> r = fresh(SHIFT, 1, cb);
        put(r, 0, 0);
        final Region<Bag> a = r.getRegionAt(cx(0, 3), cx(0, 3));
        t.check("READY 区域可进入 TICKING", r.tryMarkTicking(a));
        t.eq("状态 TICKING", RegionState.TICKING, a.state());
        t.check("重复 tryMarkTicking 失败", !r.tryMarkTicking(a));

        put(r, 0, 2);                        // 与 TICKING 区域相邻（距离 1）
        final Region<Bag> b = r.getRegionAt(cx(0, 3), cx(2, 3));
        t.check("不得挂入 TICKING 区域，必须新建", b != a && r.regionCount() == 2,
                "regionCount=" + r.regionCount());
        t.eq("TICKING 区域段集不变（I2）", 5, a.sectionCount());
        t.noViolations("不变量（含 I2 快照校验）", r.verifyInvariants());

        r.addChunk(cx(0, 5), cx(0, 5));      // 同段追加第二个区块
        t.eq("同段追加区块不影响段集", 5, a.sectionCount());
        t.eq("区块计数增加", 3, r.chunkCount());
        t.noViolations("不变量（含 I2 快照校验）", r.verifyInvariants());

        t.check("释放点返回 true", r.markNotTicking(a));
        t.eq("释放后收敛为一区", 1, r.regionCount());
        t.eq("收敛后段数 9", 9, r.regionsSnapshot().get(0).sectionCount());
        t.check("对非 TICKING 区域释放返回 false", !r.markNotTicking(r.regionsSnapshot().get(0)));
        t.noViolations("不变量（稳定态）", r.verifyInvariants(true));
    }

    /** 状态迁移契约（docs/06 §7）：分裂按坐标分流、死段状态丢弃。 */
    public static void migration(final T t) {
        t.group("状态迁移契约（docs/06 §7）");
        final Bag.Callbacks cb = new Bag.Callbacks();
        final Regionizer<Bag> r = fresh(SHIFT, 1, cb);
        final int[][] sections = {{0, 0}, {0, 2}, {0, 4}};
        Region<Bag> region = null;
        for (final int[] s : sections) {
            put(r, s[0], s[1]);
            region = r.getRegionAt(cx(s[0], 3), cx(s[1], 3));
        }
        for (final int[] s : sections) {
            region.data().entries.put(SectionKey.pack(s[0], s[1]), "v:" + s[0] + "," + s[1]);
        }
        t.eq("迁移前条目数", 3, region.data().entries.size());

        drop(r, 0, 2);
        Harness.settle(r);
        t.eq("分裂为两区", 2, r.regionCount());
        final Region<Bag> keep = r.getRegionAt(cx(0, 3), cx(0, 3));
        final Region<Bag> moved = r.getRegionAt(cx(0, 3), cx(4, 3));
        t.eq("留在原区域的条目", Set.of("v:0,0"), new HashSet<>(keep.data().entries.values()));
        t.eq("迁到新区域的条目", Set.of("v:0,4"), new HashSet<>(moved.data().entries.values()));
        t.noViolations("不变量（稳定态）", r.verifyInvariants(true));

        put(r, 0, 2);
        Harness.settle(r);
        final Region<Bag> mergedRegion = r.getRegionAt(cx(0, 3), cx(0, 3));
        t.eq("合并后条目数（段(0,2)消亡时其条目已按契约丢弃）", 2, mergedRegion.data().entries.size());
        t.eq("合并后区域数", 1, r.regionCount());
        t.noViolations("不变量（稳定态）", r.verifyInvariants(true));
    }

    public static void run(final T t) {
        sectionKey(t);
        defaultConfig(t);
        closedFormAlive(t);
        bridgeSection(t);
        split(t);
        merge(t);
        ticking(t);
        migration(t);
    }

    public static void main(final String[] args) {
        final T t = new T();
        run(t);
        System.exit(t.summary());
    }
}
