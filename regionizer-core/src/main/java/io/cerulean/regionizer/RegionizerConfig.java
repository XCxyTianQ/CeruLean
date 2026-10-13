package io.cerulean.regionizer;

/**
 * 区域化拓扑参数。
 *
 * <p><b>这些是语义参数，不是性能旋钮</b>：改一个数就可能改变区域划分结果，
 * 因此 C1 的模型检验会在多组配置下运行。</p>
 *
 * <p>故意<b>不包含</b>的两项（属于 C3 的性能优化，引入时必须保持本层不变量与模型检验通过）：</p>
 * <ul>
 *   <li>{@code createRadius}（预建邻居段以减少建/删段抖动）——C1 按需建段；</li>
 *   <li>阈值化重算（{@code minSectionRecalcCount} / {@code maxDeadRegionPercent}）——
 *       C1 采用<b>精确语义</b>：每次释放点都回收死段并重算连通分量。</li>
 * </ul>
 */
public final class RegionizerConfig {

    /** 区块坐标 → 段坐标的位移。regionShift=4 ⇒ 每段 16×16 区块。 */
    public final int regionShift;

    /**
     * 合并半径（段坐标的 Chebyshev 距离）：距离 ≤ 该值的两个存活段属于同一区域（传递闭包）。
     * 它同时定义了"区域应当收敛到哪个划分"这一语义判据。
     */
    public final int mergeRadius;

    /**
     * 是否在进入 TICKING 时复制一份段 key 快照（用于校验不变量 I2）。
     *
     * <p><b>默认关闭</b>：快照是 O(段数) 的分配，实测在大区域上是每次释放的主要成本
     * （2496 段 ≈ 500 µs）。测试与不变量巡检把它打开，生产路径保持关闭。</p>
     */
    public final boolean trackTickingSections;

    /** 邻居语义（物化范围 == 存活判定范围）。默认 {@link Neighbourhood#CARDINAL_4}。 */
    public final Neighbourhood neighbourhood;

    public RegionizerConfig(final int regionShift, final int mergeRadius) {
        this(regionShift, mergeRadius, false, Neighbourhood.SQUARE_3X3);   // 默认上游语义
    }

    public RegionizerConfig(final int regionShift, final int mergeRadius, final boolean trackTickingSections) {
        this(regionShift, mergeRadius, trackTickingSections, Neighbourhood.SQUARE_3X3);   // 默认上游语义
    }

    public RegionizerConfig(final int regionShift, final int mergeRadius, final boolean trackTickingSections,
                            final Neighbourhood neighbourhood) {
        if (regionShift < 0 || regionShift > 16) {
            throw new IllegalArgumentException("regionShift 越界: " + regionShift);
        }
        if (mergeRadius < 1 || mergeRadius > 8) {
            throw new IllegalArgumentException("mergeRadius 必须落在 [1,8]: " + mergeRadius);
        }
        this.regionShift = regionShift;
        this.mergeRadius = mergeRadius;
        this.trackTickingSections = trackTickingSections;
        this.neighbourhood = neighbourhood;
    }

    /**
     * 默认配置：16×16 区块的段、**合并半径 2 段（32 区块）**、**3×3 方形邻居（上游语义）**。
     *
     * <p>为什么默认用上游的方形而不是我们更省的 4 正邻：**服务器侧技术机械的尺寸不可估量**，
     * 采用上游已被大量服务器验证过的语义，裕度更大、争议更小（实测分区分界相差约 1 区块）。
     * 同时注意：上游的 {@code regionSectionMergeRadius = 1}（16 区块），我们取 2（32 区块），
     * 即在"大机械是否被切开"这个维度上比上游更保守一倍。</p>
     */
    public static RegionizerConfig defaults() {
        return new RegionizerConfig(4, 2, false, Neighbourhood.SQUARE_3X3);
    }

    public RegionizerConfig withMergeRadius(final int mergeRadius) {
        return new RegionizerConfig(this.regionShift, mergeRadius, this.trackTickingSections, this.neighbourhood);
    }

    public RegionizerConfig withShift(final int regionShift) {
        return new RegionizerConfig(regionShift, this.mergeRadius, this.trackTickingSections, this.neighbourhood);
    }

    /** 换邻居语义（例如 {@link Neighbourhood#SQUARE_3X3} 等价上游 createRadius=1）。 */
    public RegionizerConfig withNeighbourhood(final Neighbourhood newNeighbourhood) {
        return new RegionizerConfig(this.regionShift, this.mergeRadius, this.trackTickingSections, newNeighbourhood);
    }

    /** 打开 I2 快照（测试/巡检用）。 */
    public RegionizerConfig withTickingSnapshot() {
        return new RegionizerConfig(this.regionShift, this.mergeRadius, true, this.neighbourhood);
    }

    @Override
    public String toString() {
        return "Config{shift=" + this.regionShift + ", mergeRadius=" + this.mergeRadius
                + ", tickingSnapshot=" + this.trackTickingSections
                + ", neighbourhood=" + this.neighbourhood + '}';
    }
}
