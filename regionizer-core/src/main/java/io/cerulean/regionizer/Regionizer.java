package io.cerulean.regionizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 区域化器：段 → 区域 的归属引擎（见 docs/06 区域化设计规格）。
 *
 * <h2>语义（C1 的精确规则）</h2>
 * <ol>
 *   <li><b>S1 存活（闭式定义，无状态）</b>：存活段 = <b>有区块的段</b> ∪ <b>这些段的四邻</b>。
 *       为维持该闭式集合，{@code addChunk} 会<b>物化</b>该段的四个四邻段（即使它们还没有区块）——
 *       否则间隔 2 的两段之间缺少"桥接段"，区域会被不必要地切碎
 *       （这正是上游 {@code createRadius} 的作用；本实现固定为 1，因为一级存活规则下更大取值没有意义）。</li>
 *   <li><b>S2 划分</b>：在"稳定态"（每个区域都经历过释放点后），区域 = 存活段在
 *       Chebyshev 距离 ≤ {@code mergeRadius} 邻接下连通分量的集合；死段不属任何区域。</li>
 *   <li><b>S3 变更时机</b>：段集只在两个时机变化——{@code addChunk}（挂载/吸收）与
 *       {@code markNotTicking}（释放点：执行合并 → 回收死段 → 必要时分裂）。
 *       {@code removeChunk} 绝不改变拓扑。</li>
 *   <li><b>S4 TICKING 不可变</b>：TICKING 区域的<b>段集</b>不可变（既不新增段、也不被合并或被分裂）。
 *       其它区域若与它相邻，只能先各自成区，等它释放时再收敛。
 *       注意：区块计数在 TICKING 期间可变（区块加载必须能发生）。</li>
 * </ol>
 *
 * <h2>与上游实现的两处故意不同</h2>
 * <ul>
 *   <li>核心不依赖 Minecraft（可在毫秒级单测里穷举验证，见 study/folia/notes/00 的反面教材）；</li>
 *   <li>不使用 ThreadLocal 承载状态——本类只做拓扑，区域自有状态由 {@link Region#data()} 持有，
 *       并通过 {@link RegionizerCallbacks#mergeState}/{@link RegionizerCallbacks#splitState} 迁移。</li>
 * </ul>
 */
public final class Regionizer<D> {

    /**
     * 邻居偏移由 {@link RegionizerConfig#neighbourhoodRadius} 决定（物化范围 == 存活判定范围）。
     * 默认 4 正邻；{@code neighbourhoodRadius=1} 时为 3×3 方形（等价上游 createRadius=1）。
     */
    private int[][] neighbours() {
        return this.config.neighbourhood.offsets();
    }

    private final RegionizerConfig config;
    private final Supplier<D> dataFactory;
    private final RegionizerCallbacks<D> callbacks;

    private final StampedLock lock = new StampedLock();
    private final Map<Long, Section<D>> sections = new HashMap<>();
    private final Map<Long, Region<D>> regions = new LinkedHashMap<>();
    private long nextRegionId = 1L;
    private int chunkCount;

    public Regionizer(final RegionizerConfig config, final Supplier<D> dataFactory,
                      final RegionizerCallbacks<D> callbacks) {
        this.config = config;
        this.dataFactory = dataFactory;
        this.callbacks = callbacks;
    }

    public RegionizerConfig config() {
        return this.config;
    }

    // ────────────────────────────── 公共 API ──────────────────────────────

    /** 登记一个区块；必要时建段、挂载区域、登记合并。返回该区块所属区域（不为 null）。 */
    public Region<D> addChunk(final int chunkX, final int chunkZ) {
        final long stamp = this.lock.writeLock();
        try {
            return this.addChunkLocked(chunkX, chunkZ);
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    /** 注销一个区块。<b>不改变拓扑</b>（S3）。返回 false 表示该位置本就没有区块。 */
    public boolean removeChunk(final int chunkX, final int chunkZ) {
        final long stamp = this.lock.writeLock();
        try {
            final Section<D> section = this.sections.get(this.sectionKey(chunkX, chunkZ));
            if (section == null || section.chunkCount == 0) {
                return false;
            }
            final boolean becameEmpty = --section.chunkCount == 0;
            --this.chunkCount;
            if (becameEmpty) {
                if (section.nonEmptyNeighbours == 0 && section.region != null) {
                    section.region.markDead(section);       // 自己变死
                }
                for (final int[] d : this.neighbours()) {
                    final Section<D> n = this.sections.get(SectionKey.pack(section.x + d[0], section.z + d[1]));
                    if (n != null) {
                        n.nonEmptyNeighbours--;
                        if (n.chunkCount == 0 && n.nonEmptyNeighbours == 0 && n.region != null) {
                            n.region.markDead(n);           // 邻居因我而变死
                        }
                    }
                }
            }
            return true;
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    /** 查询区块所属区域（可能为 null：该段不存在或已死）。 */
    public Region<D> getRegionAt(final int chunkX, final int chunkZ) {
        final long stamp = this.lock.readLock();
        try {
            return this.getRegionAtUnsynchronised(chunkX, chunkZ);
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    /** 不取锁的查询：<b>仅限已持有读/写锁，或单线程测试时</b>使用。 */
    public Region<D> getRegionAtUnsynchronised(final int chunkX, final int chunkZ) {
        final Section<D> section = this.sections.get(this.sectionKey(chunkX, chunkZ));
        return section == null ? null : section.region;
    }

    public Region<D> getRegionForSection(final int sectionX, final int sectionZ) {
        final long stamp = this.lock.readLock();
        try {
            final Section<D> section = this.sections.get(SectionKey.pack(sectionX, sectionZ));
            return section == null ? null : section.region;
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    /**
     * 取 tick 并把本区域**绑定**给 {@code ownerThreadId}（B2 的归属来源）。
     *
     * <p>语义与 {@link #tryMarkTicking(Region)} 完全一致，只多记录"谁在 tick"。
     * 传 {@code -1} 表示不绑定线程（等价于旧行为：释放前不参与归属校验）。</p>
     */
    public boolean tryMarkTicking(final Region<D> region, final long ownerThreadId) {
        final boolean ok = this.tryMarkTicking(region);
        if (ok) {
            region.ownerThreadId = ownerThreadId;
        }
        return ok;
    }

    /**
     * 查询"该区块位置归哪个线程"。
     *
     * @return 拥有该位置区域的 tick 线程 id；{@code -1} = 无人拥有
     *         （该位置没有区域，或区域不在 TICKING，或区域未绑定线程）
     */
    public long ownerThreadIdAt(final int chunkX, final int chunkZ) {
        final Section<D> section = this.sections.get(
                SectionKey.pack(SectionKey.chunkToSection(chunkX, this.config.regionShift),
                                SectionKey.chunkToSection(chunkZ, this.config.regionShift)));
        if (section == null) {
            return -1L;
        }
        final Region<D> region = section.region;
        if (region == null || region.state != RegionState.TICKING) {
            return -1L;
        }
        return region.ownerThreadId;
    }

    /** 尝试把区域置为 TICKING（不变量 I3：READY 且无挂起合并）；不绑定线程。 */
    public boolean tryMarkTicking(final Region<D> region) {
        final long stamp = this.lock.writeLock();
        try {
            if (region.state != RegionState.READY || region.sections.isEmpty()) {
                return false;
            }
            region.state = RegionState.TICKING;
            if (this.config.trackTickingSections) {      // 生产默认关闭：这是 O(段数) 的分配
                final HashSet<Long> snapshot = new HashSet<>();
                for (final Section<D> s : region.sections) {
                    snapshot.add(s.key);
                }
                region.tickingSections = snapshot;
            }
            return true;
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    /**
     * 释放点：区域结束 tick。执行合并 → 回收死段 → 必要时分裂。
     * 返回该区域是否仍然存活（被合并进别的区域时为 false，调用方应丢弃引用）。
     */
    public boolean markNotTicking(final Region<D> region) {
        final long stamp = this.lock.writeLock();
        try {
            if (region.state != RegionState.TICKING) {
                return false;
            }
            region.tickingSections = null;
            region.state = RegionState.READY;

            // 1) 收敛：把 mergeRadius 内所有"存活且非 TICKING"的邻居区域吸收进来。
            //    采用"谁释放谁吸收"，因此不存在握手机制，也就不可能出现
            //    "两个区域互相等待对方释放" 的死锁（见 README 的协议修订说明）。
            this.convergeLocked(region);

            // 2) 重算：回收死段 → 连通分量 → 必要时分裂
            this.recomputeLocked(region);

            if (region.state == RegionState.DEAD) {
                return false;
            }
            this.updateStateLocked(region);
            return true;
        } finally {
            this.lock.unlockWrite(stamp);
        }
    }

    public List<Region<D>> regionsSnapshot() {
        final long stamp = this.lock.readLock();
        try {
            return new ArrayList<>(this.regions.values());
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    public int regionCount() {
        final long stamp = this.lock.readLock();
        try {
            return this.regions.size();
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    public int chunkCount() {
        final long stamp = this.lock.readLock();
        try {
            return this.chunkCount;
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    public int sectionCount() {
        final long stamp = this.lock.readLock();
        try {
            return this.sections.size();
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    public int aliveSectionCount() {
        final long stamp = this.lock.readLock();
        try {
            int n = 0;
            for (final Section<D> s : this.sections.values()) {
                if (s.isAlive()) {
                    n++;
                }
            }
            return n;
        } finally {
            this.lock.unlockRead(stamp);
        }
    }

    // ────────────────────────────── 内部：挂载 ──────────────────────────────

    private long sectionKey(final int chunkX, final int chunkZ) {
        return SectionKey.pack(SectionKey.chunkToSection(chunkX, this.config.regionShift),
                SectionKey.chunkToSection(chunkZ, this.config.regionShift));
    }

    private Region<D> addChunkLocked(final int chunkX, final int chunkZ) {
        final int sx = SectionKey.chunkToSection(chunkX, this.config.regionShift);
        final int sz = SectionKey.chunkToSection(chunkZ, this.config.regionShift);
        final long key = SectionKey.pack(sx, sz);

        Section<D> section = this.sections.get(key);
        if (section == null) {
            section = new Section<>(key, sx, sz);
            for (final int[] d : this.neighbours()) {
                final Section<D> n = this.sections.get(SectionKey.pack(sx + d[0], sz + d[1]));
                if (n != null && n.chunkCount > 0) {
                    section.nonEmptyNeighbours++;
                }
            }
            this.sections.put(key, section);
        }

        if (section.chunkCount == 0) {
            // 本段由空变非空：通知【已存在】的四邻（它们多了一个"非空邻居"）
            for (final int[] d : this.neighbours()) {
                final Section<D> n = this.sections.get(SectionKey.pack(sx + d[0], sz + d[1]));
                if (n != null) {
                    if (n.chunkCount == 0 && n.nonEmptyNeighbours == 0 && n.region != null) {
                        n.region.markAlive(n);      // 原本是死段，现在靠邻居复活
                    }
                    n.nonEmptyNeighbours++;
                }
            }
        }
        section.chunkCount++;
        this.chunkCount++;
        if (section.region != null) {
            section.region.markAlive(section);
        }

        if (section.region == null) {
            this.attachLocked(section);
        }
        // 物化四邻（S1 的闭式集合）：缺失的邻居此刻创建，其"非空邻居"计数会自然包含本段。
        for (final int[] d : this.neighbours()) {
            final int nx = sx + d[0];
            final int nz = sz + d[1];
            final long nk = SectionKey.pack(nx, nz);
            Section<D> neighbour = this.sections.get(nk);
            if (neighbour == null) {
                neighbour = new Section<>(nk, nx, nz);
                for (final int[] e : this.neighbours()) {
                    final Section<D> nn = this.sections.get(SectionKey.pack(nx + e[0], nz + e[1]));
                    if (nn != null && nn.chunkCount > 0) {
                        neighbour.nonEmptyNeighbours++;
                    }
                }
                this.sections.put(nk, neighbour);
            }
            if (neighbour.region == null && neighbour.isAlive()) {
                this.attachLocked(neighbour);
            } else if (neighbour.region != null) {
                this.seedLocked(neighbour.region, neighbour);
            }
        }
        return section.region;
    }

    /** 把新段挂到某个区域：优先挂到"非 TICKING"的邻居区域，否则新建区域。 */
    private void attachLocked(final Section<D> section) {
        final LinkedHashSet<Region<D>> candidates = new LinkedHashSet<>();
        this.forEachInRadius(section.x, section.z, this.config.mergeRadius, s -> {
            if (s != section && s.isAlive() && s.region != null && s.region.state != RegionState.DEAD) {
                candidates.add(s.region);
            }
        });

        Region<D> target = null;
        for (final Region<D> r : candidates) {
            if (r.state != RegionState.TICKING) {
                target = r;
                break;
            }
        }
        if (target == null) {
            // 没有可挂载的区域（含"邻居都是 TICKING"的情况）→ 新建；等对方释放时收敛（S4）
            target = this.createRegionLocked();
        }

        section.region = target;
        final boolean first = target.sections.isEmpty();
        target.sections.add(section);
        if (first) {
            this.callbacks.onRegionActive(target);
        }

        // 性能关键：只扫【新段邻域】即可判断能否立即吸收别的区域；
        // 不能立即吸收的（例如对方正在 TICKING）登记到种子里，留给释放点。
        this.seedLocked(target, section);
        this.updateStateLocked(target);
    }

    /** 登记一个"可能产生跨区域邻接"的种子段；若邻域内有可吸收的区域则立即吸收。 */
    private void seedLocked(final Region<D> region, final Section<D> seed) {
        if (region.state == RegionState.TICKING) {
            // S4/I2：TICKING 区域的段集不可变 ⇒ 只登记提示（不吸收、不合并），
            // 等它释放时再收敛。登记种子本身不改段集，是允许的。
            region.convergenceSeeds.add(seed.key);
            return;
        }
        boolean pending = false;
        for (int dx = -this.config.mergeRadius; dx <= this.config.mergeRadius; dx++) {
            for (int dz = -this.config.mergeRadius; dz <= this.config.mergeRadius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                final Section<D> other = this.sections.get(SectionKey.pack(seed.x + dx, seed.z + dz));
                if (other == null || other == seed || !other.isAlive()) {
                    continue;
                }
                if (other.region == null) {
                    other.region = region;                       // 孤儿存活段：直接吸收
                    region.sections.add(other);
                    region.convergenceSeeds.add(other.key);
                } else if (other.region != region) {
                    if (other.region.state == RegionState.TICKING || other.region.state == RegionState.DEAD) {
                        pending = true;                          // 等它释放
                    } else {
                        region.convergenceSeeds.addAll(other.region.convergenceSeeds);
                        this.mergeLocked(region, other.region);   // 语义与释放点吸收一致
                    }
                }
            }
        }
        if (pending) {
            region.convergenceSeeds.add(seed.key);
        }
    }

    /** 释放点的收敛：只扫"可能产生跨区域邻接"的种子段（正常 tick 下种子为空 ⇒ 零成本）。 */
    private void convergeLocked(final Region<D> region) {
        Region<D> other;
        while ((other = this.findAdjacentLocked(region)) != null) {
            region.convergenceSeeds.addAll(other.convergenceSeeds);
            this.mergeLocked(region, other);
        }
    }

    /** 在【种子段】邻域里找可并入的区域；种子扫完仍无可并入者则清空种子。 */
    private Region<D> findAdjacentLocked(final Region<D> region) {
        if (region.convergenceSeeds.isEmpty()) {
            return null;
        }
        final int r = this.config.mergeRadius;
        final java.util.Iterator<Long> it = region.convergenceSeeds.iterator();
        while (it.hasNext()) {
            final long key = it.next();
            final int sx = SectionKey.unpackX(key);
            final int sz = SectionKey.unpackZ(key);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    final Section<D> other = this.sections.get(SectionKey.pack(sx + dx, sz + dz));
                    if (other == null || !other.isAlive()) {
                        continue;
                    }
                    if (other.region == null) {
                        other.region = region;                   // 孤儿存活段：直接吸收
                        region.sections.add(other);
                    } else if (other.region != region && other.region.state != RegionState.TICKING
                            && other.region.state != RegionState.DEAD) {
                        return other.region;
                    }
                }
            }
            it.remove();     // 该种子已扫过：若邻域里有 TICKING 区域，会在别处重新登记
        }
        // 复查：区域内是否存在与 TICKING 区域相邻的段（有则继续保留种子，等待对方释放）
        for (final Section<D> self : region.sections) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    final Section<D> other = this.sections.get(SectionKey.pack(self.x + dx, self.z + dz));
                    if (other != null && other.isAlive() && other.region != null && other.region != region
                            && other.region.state == RegionState.TICKING) {
                        region.convergenceSeeds.add(self.key);
                        return null;     // 等对方释放（S4）
                    }
                }
            }
        }
        return null;
    }

    /** 回收死段；**仅当确实回收了死段**才重算连通分量（形状没变就不可能分裂）。 */
    private void recomputeLocked(final Region<D> region) {
        if (region.state == RegionState.DEAD) {
            return;
        }
        // 1) 回收死段（只遍历 deadSections，代价与该区域死段数成正比；常见情况为空 ⇒ 零成本）
        boolean removed = false;
        if (!region.deadSections.isEmpty()) {
            for (final java.util.Iterator<Long> it = region.deadSections.iterator(); it.hasNext(); ) {
                final long key = it.next();
                final Section<D> s = this.sections.get(key);
                if (s == null) {
                    it.remove();
                    continue;
                }
                if (s.isAlive()) {              // 期间复活了（并发 addChunk）：只撤销登记
                    it.remove();
                    continue;
                }
                it.remove();
                region.sections.remove(s);
                s.region = null;
                if (s.chunkCount == 0) {
                    this.sections.remove(s.key);
                }
                removed = true;
            }
        }
        if (region.sections.isEmpty()) {
            this.callbacks.onRegionDeactivate(region);
            this.destroyRegionLocked(region);
            return;
        }
        if (!removed) {
            return;             // 形状未变：跳过 BFS（这是释放点成本从 ms 级降到 µs 级的关键）
        }

        // 2) 连通分量（不变量 I1/I2 的语义判据 S2）
        final List<List<Section<D>>> components = this.componentsOf(region.sections);
        if (components.size() <= 1) {
            return;
        }

        // 3) 分裂：保留包含最小 key 的分量，其余各自成新区域
        components.sort((a, b) -> Long.compare(minKey(a), minKey(b)));
        this.callbacks.onRegionPreSplit(region);

        final Map<Long, D> newDataByKey = new LinkedHashMap<>();
        for (final Section<D> s : region.sections) {
            newDataByKey.put(s.key, region.data);
        }
        final List<Region<D>> created = new ArrayList<>();
        for (int i = 1; i < components.size(); i++) {
            final List<Section<D>> comp = components.get(i);
            final Region<D> nr = this.createRegionLocked();
            for (final Section<D> s : comp) {
                region.sections.remove(s);
                s.region = nr;
                nr.sections.add(s);
                newDataByKey.put(s.key, nr.data);
            }
            nr.convergenceSeeds.addAll(region.convergenceSeeds);
            created.add(nr);
        }
        this.callbacks.splitState(region.data, this.config.regionShift, newDataByKey, newDataByKey.values());
        for (final Region<D> nr : created) {
            this.updateStateLocked(nr);
            this.callbacks.onRegionActive(nr);
            this.callbacks.onRegionSplit(region, nr);
        }
    }

    /** 区域段集在 {@code mergeRadius} 邻接下的连通分量。 */
    private List<List<Section<D>>> componentsOf(final Collection<Section<D>> in) {
        final Map<Long, Section<D>> byKey = new HashMap<>();
        for (final Section<D> s : in) {
            byKey.put(s.key, s);
        }
        final Set<Long> seen = new HashSet<>();
        final List<List<Section<D>>> out = new ArrayList<>();
        for (final Section<D> start : in) {
            if (!seen.add(start.key)) {
                continue;
            }
            final List<Section<D>> comp = new ArrayList<>();
            comp.add(start);
            final ArrayDeque<Section<D>> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                final Section<D> cur = queue.poll();
                this.forEachInRadius(cur.x, cur.z, this.config.mergeRadius, n -> {
                    if (n != cur && byKey.containsKey(n.key) && seen.add(n.key)) {
                        comp.add(n);
                        queue.add(n);
                    }
                });
            }
            out.add(comp);
        }
        return out;
    }

    private static <T> long minKey(final List<Section<T>> comp) {
        long min = Long.MAX_VALUE;
        for (final Section<T> s : comp) {
            if (s.key < min) {
                min = s.key;
            }
        }
        return min;
    }

    // ────────────────────────────── 内部：区域生命周期 ──────────────────────────────

    private Region<D> createRegionLocked() {
        final Region<D> region = new Region<>(this.nextRegionId++, this.dataFactory.get());
        this.regions.put(region.id(), region);
        this.callbacks.onRegionCreate(region);
        region.state = RegionState.READY; // 尚无段、无挂起合并
        return region;
    }

    private void destroyRegionLocked(final Region<D> region) {
        region.state = RegionState.DEAD;
        this.regions.remove(region.id());
        region.data = null;
        this.callbacks.onRegionDestroy(region);
    }

    /** 把 {@code from} 的段与状态并入 {@code into}，然后销毁 {@code from}。 */
    private void mergeLocked(final Region<D> into, final Region<D> from) {
        if (into == from || from.state == RegionState.DEAD) {
            return;
        }
        this.callbacks.onRegionPreMerge(from, into);
        final long tickOffset = into.tickCount - from.tickCount;
        final D fromData = from.data;
        from.data = null;             // 先摘掉，避免回调抛异常时状态悬挂
        this.callbacks.mergeState(fromData, into.data, tickOffset);

        for (final Section<D> s : from.sections) {
            s.region = into;
            into.sections.add(s);
        }
        into.convergenceSeeds.addAll(from.convergenceSeeds);
        from.convergenceSeeds.clear();
        into.deadSections.addAll(from.deadSections);      // 死段随段一起搬（上游 mergeInto 同做法）
        from.deadSections.clear();
        from.sections.clear();
        this.destroyRegionLocked(from);
        this.updateStateLocked(into);
    }

    private void updateStateLocked(final Region<D> region) {
        if (region.state == RegionState.DEAD || region.state == RegionState.TICKING) {
            return;
        }
        region.state = RegionState.READY;
    }

    // ────────────────────────────── 内部：工具 ──────────────────────────────

    private void forEachInRadius(final int sx, final int sz, final int radius, final Consumer<Section<D>> consumer) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                final Section<D> n = this.sections.get(SectionKey.pack(sx + dx, sz + dz));
                if (n != null) {
                    consumer.accept(n);
                }
            }
        }
    }

    // ────────────────────────────── 不变量自检（供测试调用） ──────────────────────────────

    /**
     * 校验<b>任何时刻都成立</b>的不变量：I1（唯一归属）、I2（TICKING 段集不变）、
     * I5（非空邻居计数）、以及区块总数一致性。返回违规描述列表。
     */
    public List<String> verifyInvariants() {
        return this.verifyInvariants(false);
    }

    /**
     * @param settled 为 true 时额外校验<b>只在稳定态成立</b>的不变量：
     *                I3'（已收敛：mergeRadius 内没有属于其它区域的存活段）
     *                与 I6（死段已回收，不再属于任何区域）。
     *                注意：释放点之前"死段仍挂在区域上"是<b>正常</b>的（语义 S3）。
     */
    public List<String> verifyInvariants(final boolean settled) {
        final long stamp = this.lock.readLock();
        try {
            final List<String> errors = new ArrayList<>();
            final Set<Long> assigned = new HashSet<>();
            int sumChunks = 0;

            for (final Region<D> region : this.regions.values()) {
                if (region.state == RegionState.DEAD) {
                    errors.add("DEAD 区域仍在表中: " + region.id());
                }
                // I3'：READY 区域在其 mergeRadius 内不应存在属于其它区域的存活段
                if (settled && region.state == RegionState.READY && region.tickingSections == null) {
                    for (final Section<D> s : region.sections) {
                        this.forEachInRadius(s.x, s.z, this.config.mergeRadius, n -> {
                            if (n.isAlive() && n.region != region && n.region != null
                                    && n.region.state != RegionState.TICKING) {
                                errors.add("I3' 违规：READY 区域 " + region.id() + " 旁存在未合并的存活段 "
                                        + SectionKey.toString(n.key) + "（属于 " + n.region.id() + "）");
                            }
                        });
                    }
                }
                // I2：TICKING 期间段集不可变
                if (region.state == RegionState.TICKING && region.tickingSections != null) {
                    final Set<Long> now = new HashSet<>();
                    for (final Section<D> s : region.sections) {
                        now.add(s.key);
                    }
                    if (!now.equals(region.tickingSections)) {
                        errors.add("I2 违规：TICKING 区域段集发生变化 " + region.id());
                    }
                }
                for (final Section<D> s : region.sections) {
                    if (!assigned.add(s.key)) {
                        errors.add("I1 违规：段 " + SectionKey.toString(s.key) + " 同时属于多个区域");
                    }
                    if (s.region != region) {
                        errors.add("I1 违规：段 " + SectionKey.toString(s.key) + " 的反向指针不一致");
                    }
                    sumChunks += s.chunkCount;
                    if (settled && !s.isAlive()) {
                        errors.add("I6 违规：死段仍属区域 " + SectionKey.toString(s.key));
                    }
                }
            }

            // I5 + 孤儿检查：段的 nonEmptyNeighbours 必须等于四邻中区块数 > 0 的个数
            for (final Section<D> s : this.sections.values()) {
                int expect = 0;
                for (final int[] d : this.neighbours()) {
                    final Section<D> n = this.sections.get(SectionKey.pack(s.x + d[0], s.z + d[1]));
                    if (n != null && n.chunkCount > 0) {
                        expect++;
                    }
                }
                if (s.nonEmptyNeighbours != expect) {
                    errors.add("I5 违规：段 " + SectionKey.toString(s.key) + " 非空邻居计数 " + s.nonEmptyNeighbours
                            + " 应为 " + expect);
                }
                if (s.isAlive() && s.region == null) {
                    errors.add("孤儿存活段（无区域）: " + SectionKey.toString(s.key));
                }
                if (s.region != null && !assigned.contains(s.key)) {
                    errors.add("I1 违规：段 " + SectionKey.toString(s.key) + " 指向区域但其不在区域段集中");
                }
            }

            if (sumChunks != this.chunkCount) {
                errors.add("区块总数不一致：段合计 " + sumChunks + " / 计数 " + this.chunkCount);
            }
            return errors;
        } finally {
            this.lock.unlockRead(stamp);
        }
    }
}
