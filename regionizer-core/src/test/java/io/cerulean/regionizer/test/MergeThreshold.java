package io.cerulean.regionizer.test;

import io.cerulean.regionizer.Neighbourhood;
import io.cerulean.regionizer.Region;
import io.cerulean.regionizer.Regionizer;
import io.cerulean.regionizer.RegionizerConfig;

/** 量的是一条"有区块的段"链在多远间距下仍会被并成一个区域（护城河会桥接，所以不是 mergeRadius 本身）。 */
public final class MergeThreshold {

    private static final int PER = 16;

    private MergeThreshold() {
    }

    public static void main(final String[] args) {
        System.out.println("== 有区块段之间的合并阈值（间距单位：段；段=16 区块）");
        System.out.printf("%-12s %-8s %-10s%n", "邻居语义", "mergeR", "合并的最大间距");
        for (final Neighbourhood nb : new Neighbourhood[]{Neighbourhood.CARDINAL_4, Neighbourhood.SQUARE_3X3}) {
            for (final int mergeR : new int[]{1, 2, 3}) {
                int threshold = 0;
                for (int spacing = 1; spacing <= 8; spacing++) {
                    if (connects(nb, mergeR, spacing)) {
                        threshold = spacing;
                    } else {
                        break;
                    }
                }
                System.out.printf("%-12s %-8d %-10d%n", nb, mergeR, threshold);
            }
        }
        System.out.println("（两个端点段，相距 spacing 段，各自有区块；是否落在同一区域）");
    }

    private static boolean connects(final Neighbourhood nb, final int mergeR, final int spacing) {
        final Regionizer<Bag> r = new Regionizer<>(new RegionizerConfig(4, mergeR, false, nb), Bag::new, new Bag.Callbacks());
        r.addChunk(10 * PER + 1, 1);
        r.addChunk((10 + spacing) * PER + 1, 1);
        Harness.settle(r);
        final Region<Bag> a = r.getRegionAt(10 * PER + 1, 1);
        final Region<Bag> b = r.getRegionAt((10 + spacing) * PER + 1, 1);
        return a != null && b != null && a.id() == b.id();
    }
}
