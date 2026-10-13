package io.cerulean.regionizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 区域：tick 的调度单位（见 docs/06 §2、§5）。
 *
 * <p>不变量：</p>
 * <ul>
 *   <li><b>I2</b>：{@code TICKING} 期间<b>段集不可变</b>（不增不删、不被吸收）。
 *       区块计数仍可变（区块加载必须能发生）。</li>
 *   <li><b>I3'</b>（C1 修订版，替代原 I3/I4）：释放点过后，区域在
 *       {@code mergeRadius} 内的所有存活段都属于自己——即已收敛。</li>
 * </ul>
 */
public final class Region<D> {

    private final long id;

    RegionState state = RegionState.TRANSIENT;

    /** 有序，保证合并/分裂结果确定（便于测试与排障）。 */
    final Set<Section<D>> sections = new LinkedHashSet<>();

    /** TICKING 期间段集的快照，用于校验不变量 I2。 */
    Set<Long> tickingSections;

    /**
     * 「可能产生跨区域邻接」的种子段 key（性能关键）。
     *
     * <p>释放点需要把 radius 内可合并的邻居区域并进来。若每次都扫全区域的段，
     * 代价是 O(段数 × (2r+1)²)——实测在 2500 段的区域上要 18 ms/次，无法接受。
     * 只有<b>新增段</b>（以及被并入区域带来的段）才可能产生新的跨区域邻接，
     * 因此只记录这些段的 key，释放时扫这些种子即可（正常 tick 通常是空集 ⇒ 零成本）。</p>
     */
    final java.util.Set<Long> convergenceSeeds = new java.util.LinkedHashSet<>();

    /**
     * 本区域内**已死**的段（无区块且无非空邻居）——采纳上游 `deadSections` 的做法。
     *
     * <p>作用：释放点不必遍历全部段去找死段（实测 2500 段的区域内遍历+BFS 要 15 ms），
     * 只需看这个集合；并且<b>仅当真的回收了死段</b>才做"连通分量/分裂"检查
     * （形状没变就不可能分裂）。</p>
     */
    final java.util.Set<Long> deadSections = new java.util.LinkedHashSet<>();

    /** 把段登记为死段（幂等）。 */
    void markDead(final Section<D> section) {
        this.deadSections.add(section.key);
    }

    /** 段重新存活时把它从死段集合里删掉。 */
    void markAlive(final Section<D> section) {
        this.deadSections.remove(section.key);
    }

    long tickCount;
    D data;

    Region(final long id, final D data) {
        this.id = id;
        this.data = data;
    }

    public long id() {
        return this.id;
    }

    public RegionState state() {
        return this.state;
    }

    public int sectionCount() {
        return this.sections.size();
    }

    /** 段的快照（只读）。区域可能随后被吸收/分裂，长生命周期使用请自行复制。 */
    public List<Section<D>> sectionsSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(this.sections));
    }

    /** 段 key 快照，便于测试做划分比较。 */
    public Set<Long> sectionKeys() {
        return Collections.unmodifiableSet(new HashSet<>(keysOf(this.sections)));
    }

    private static Set<Long> keysOf(final Set<? extends Section<?>> sections) {
        final Set<Long> out = new HashSet<>(sections.size() * 2);
        for (final Section<?> s : sections) {
            out.add(s.key);
        }
        return out;
    }

    public int chunkCount() {
        int n = 0;
        for (final Section<D> s : this.sections) {
            n += s.chunkCount;
        }
        return n;
    }

    public long tickCount() {
        return this.tickCount;
    }

    /** 由调度器推进区域时间基准；合并时据此换算 tick 偏移（{@code into - from}）。 */
    public void setTickCount(final long tickCount) {
        this.tickCount = tickCount;
    }

    public D data() {
        return this.data;
    }

    @Override
    public String toString() {
        return "Region#" + this.id + '{' + this.state + ", sections=" + this.sections.size()
                + ", chunks=" + this.chunkCount() + '}';
    }
}
