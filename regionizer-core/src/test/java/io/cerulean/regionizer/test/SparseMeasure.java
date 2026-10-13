package io.cerulean.regionizer.test;

import io.cerulean.regionizer.Regionizer;
import io.cerulean.regionizer.RegionizerConfig;

/** 量"邻居半径"语义的代价：稀疏 vs 稠密下，每个区块要维护多少段。 */
public final class SparseMeasure {

    private SparseMeasure() {
    }

    public static void main(final String[] args) {
        System.out.println("== 邻居语义代价测量（我们的实现：物化+存活判据都用 4 正邻）");
        for (final int gap : new int[]{1, 2, 3, 5, 8}) {
            final int shift = 4;
            final int per = 1 << shift;
            final Regionizer<Bag> riz = new Regionizer<>(new RegionizerConfig(shift, 2), Bag::new, new Bag.Callbacks());
            int chunks = 0;
            for (int x = 0; x < 40; x += gap) {
                for (int z = 0; z < 40; z += gap) {
                    riz.addChunk(x * per + 1, z * per + 1);
                    chunks++;
                }
            }
            final int sections = riz.sectionCount();
            // 上游语义（3×3 方形物化 + 3×3 存活）：每个孤立区块贡献 9 段（相互重叠时更少）
            final int upstreamUpper = chunks * 9;
            System.out.printf("  间隔 %d 段: 区块 %5d → 我们 %5d 段（%.3f 段/区块）｜上游 3×3 上限 %6d 段（%.3f）%n",
                    gap, chunks, sections, (double) sections / chunks, upstreamUpper, 9.0);
        }
    }
}
