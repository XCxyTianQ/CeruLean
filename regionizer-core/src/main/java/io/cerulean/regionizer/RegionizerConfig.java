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

    public RegionizerConfig(final int regionShift, final int mergeRadius) {
        if (regionShift < 0 || regionShift > 16) {
            throw new IllegalArgumentException("regionShift 越界: " + regionShift);
        }
        if (mergeRadius < 1 || mergeRadius > 8) {
            throw new IllegalArgumentException("mergeRadius 必须落在 [1,8]: " + mergeRadius);
        }
        this.regionShift = regionShift;
        this.mergeRadius = mergeRadius;
    }

    /** 默认：16×16 区块的段、合并半径 2。 */
    public static RegionizerConfig defaults() {
        return new RegionizerConfig(4, 2);
    }

    public RegionizerConfig withMergeRadius(final int mergeRadius) {
        return new RegionizerConfig(this.regionShift, mergeRadius);
    }

    public RegionizerConfig withShift(final int regionShift) {
        return new RegionizerConfig(regionShift, this.mergeRadius);
    }

    @Override
    public String toString() {
        return "Config{shift=" + this.regionShift + ", mergeRadius=" + this.mergeRadius + '}';
    }
}
