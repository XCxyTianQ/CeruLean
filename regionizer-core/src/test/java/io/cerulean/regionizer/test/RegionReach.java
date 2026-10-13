package io.cerulean.regionizer.test;

import java.util.HashSet;
import java.util.Set;

import io.cerulean.regionizer.Neighbourhood;
import io.cerulean.regionizer.Region;
import io.cerulean.regionizer.Regionizer;
import io.cerulean.regionizer.RegionizerConfig;

/**
 * 区域"覆盖半径"扫描：段大小 × mergeRadius × 邻居语义。
 *
 * <p>游戏侧问题：技术服的大型红石机械可能跨越很大范围。要判断"机械会不会被切成两个区域"，
 * 关键量是**同区域 reach**（两个位置相距多远仍属同一区域），它由
 * {@code mergeRadius}（段，Chebyshev）与段大小决定；邻居形状只影响约有界的一圈。</p>
 *
 * <p>原版参照：刷怪 24 格排除（欧氏）、敌怪 128 格消失（{@code MobCategory.MONSTER(...,128)}）。</p>
 */
public final class RegionReach {

    private static final int PER = 16;      // regionShift = 4

    private RegionReach() {
    }

    public static void main(final String[] args) {
        System.out.println("== 区域覆盖半径扫描（段=16 区块，1 区块=16 格）");
        System.out.println("   上游 Folia 取值：emptySectionCreateRadius=1（3×3）, regionSectionMergeRadius=1 段 = 16 区块");
        System.out.printf("%-12s %-9s %-6s %-16s %-13s %-13s%n",
                "邻居语义", "mergeR", "视距", "单玩家reach", "两玩家轴阈", "两玩家对角阈");
        for (final Neighbourhood nb : new Neighbourhood[]{Neighbourhood.CARDINAL_4, Neighbourhood.SQUARE_3X3}) {
            for (final int mergeR : new int[]{1, 2, 3, 4}) {
                for (final int view : new int[]{8, 10, 16}) {
                    measure(nb, mergeR, view);
                }
                System.out.println();
            }
        }
    }

    private static void measure(final Neighbourhood nb, final int mergeR, final int view) {
        // 单玩家：区域沿轴能延伸多远
        final Regionizer<Bag> one = build(nb, mergeR, view, 0, 0, false, 0);
        final long playerRegion = regionIdAt(one, 0, 0);
        int reach = 0;
        for (int d = 0; d <= 200; d++) {
            if (regionIdAt(one, d, 0) == playerRegion) {
                reach = d;
            }
        }

        // 两玩家：沿轴 / 沿对角多远起分属不同区域
        int axial = -1;
        int diag = -1;
        for (int d = 0; d <= 220 && (axial < 0 || diag < 0); d++) {
            if (axial < 0) {
                final Regionizer<Bag> two = build(nb, mergeR, view, d, 0, false, d);
                if (regionIdAt(two, 0, 0) != regionIdAt(two, d, 0)) {
                    axial = d;
                }
            }
            if (diag < 0) {
                final Regionizer<Bag> two = build(nb, mergeR, view, d, d, true, d);
                if (regionIdAt(two, 0, 0) != regionIdAt(two, d, d)) {
                    diag = d;
                }
            }
        }
        System.out.printf("%-12s %-9d %-6d %-16s %-13s %-13s%n", nb, mergeR, view,
                reach + " 区块(" + (reach * 16) + "格)",
                axial < 0 ? ">220" : axial + " 区块",
                diag < 0 ? ">220" : diag + " 区块");
    }

    /** 铺窗口：玩家 A 在 (0,0)，玩家 B 在 (bx,bz)（都按方形/圆视距铺满）。 */
    private static Regionizer<Bag> build(final Neighbourhood nb, final int mergeR, final int view,
                                         final int bx, final int bz, final boolean disc, final int ignored) {
        final Regionizer<Bag> r = new Regionizer<>(new RegionizerConfig(4, mergeR, false, nb), Bag::new, new Bag.Callbacks());
        final Set<Long> chunks = new HashSet<>();
        window(chunks, 0, 0, view, disc);
        if (bx != 0 || bz != 0) {
            window(chunks, bx, bz, view, disc);
        }
        for (final long key : chunks) {
            r.addChunk((int) key * PER + 1, (int) (key >> 32) * PER + 1);
        }
        Harness.settle(r);
        return r;
    }

    private static void window(final Set<Long> out, final int cx, final int cz, final int view, final boolean disc) {
        for (int dx = -view; dx <= view; dx++) {
            for (int dz = -view; dz <= view; dz++) {
                if (disc && dx * dx + dz * dz > view * view) {
                    continue;
                }
                out.add(((cx + dx) & 0xFFFFFFFFL) | ((long) (cz + dz) << 32));
            }
        }
    }

    private static long regionIdAt(final Regionizer<Bag> r, final int chunkX, final int chunkZ) {
        final Region<Bag> region = r.getRegionAt(chunkX * PER + 1, chunkZ * PER + 1);
        return region == null ? -1L : region.id();
    }
}
