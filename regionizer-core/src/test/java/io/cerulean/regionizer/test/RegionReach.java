package io.cerulean.regionizer.test;

import java.util.HashSet;
import java.util.Set;

import io.cerulean.regionizer.Neighbourhood;
import io.cerulean.regionizer.Region;
import io.cerulean.regionizer.Regionizer;
import io.cerulean.regionizer.RegionizerConfig;

/**
 * 区域"覆盖半径"测量——回答游戏侧问题：**刷怪塔/挂机点会不会被区域边界切开**。
 *
 * <p>原版规则（源码已核实）：刷怪点必须在玩家 24 格外（欧氏 `distanceToSqr &lt;= 576` 排除）、
 * 敌怪在 128 格外消失；区块状态（ticking/entity-ticking）由票据的**方形**区块距离决定。
 * 因此 farm 的有效半径 ≤ 128 格 = 8 区块。</p>
 *
 * <p>本测量给出两个数字：</p>
 * <ol>
 *   <li>单玩家：区域边界距玩家中心多远（能否覆盖 128 格）</li>
 *   <li>两玩家：相距多远才会被切成两个区域（同区域 reach）</li>
 * </ol>
 * 并对 4 正邻 / 3×3 方形两种语义分别测量，回答"邻居语义是否影响 farm"。
 */
public final class RegionReach {

    private RegionReach() {
    }

    private static final int PER = 16;   // regionShift=4

    public static void main(final String[] args) {
        System.out.println("== 区域覆盖半径测量（regionShift=4 → 段=16 区块；mergeRadius=2 → 32 区块）");
        for (final Neighbourhood nb : new Neighbourhood[]{Neighbourhood.CARDINAL_4, Neighbourhood.SQUARE_3X3}) {
            System.out.printf("%n-- 邻居语义 %s%n", nb);
            for (final int view : new int[]{8, 10, 16}) {
                // 单玩家：区域能延伸多远？
                final Regionizer<Bag> one = load(view, nb, 0, 0);
                final long playerRegion = regionIdAt(one, 0, 0);
                int maxSameRegion = 0;
                for (int d = 0; d <= 64; d++) {
                    if (regionIdAt(one, d, 0) == playerRegion) {
                        maxSameRegion = d;
                    }
                }
                final int despawnChunks = 128 / 16;   // 128 格 = 8 区块
                System.out.printf("   视距 %2d 区块: 单玩家区域沿轴延伸到 %2d 区块（%3d 格）｜128 格消失半径是否在区域内: %s%n",
                        view, maxSameRegion, maxSameRegion * 16, maxSameRegion >= despawnChunks ? "是" : "否");

                // 两玩家：相距多远才会分成两个区域？
                int splitAxial = -1;
                int splitDiagonal = -1;
                for (int d = 0; d <= 128; d++) {
                    final Regionizer<Bag> two = load(view, nb, 0, d);
                    if (splitAxial < 0 && regionIdAt(two, 0, 0) != regionIdAt(two, d, 0)) {
                        splitAxial = d;
                    }
                    final Regionizer<Bag> twoD = loadDiag(view, nb, d);
                    if (splitDiagonal < 0 && regionIdAt(twoD, 0, 0) != regionIdAt(twoD, d, d)) {
                        splitDiagonal = d;
                    }
                    if (splitAxial >= 0 && splitDiagonal >= 0) {
                        break;
                    }
                }
                System.out.printf("   视距 %2d 区块: 两玩家沿轴相距 %s 区块起分属不同区域；沿对角 %s 区块起分属不同区域（1 区块=16 格）%n",
                        view, splitAxial < 0 ? ">128" : String.valueOf(splitAxial),
                        splitDiagonal < 0 ? ">128" : String.valueOf(splitDiagonal));
            }
        }
    }

    /** 铺 loading 窗口：单玩家在 (0,0)，可选第二个玩家在 (0,dz) 或 (dz,dz)。 */
    private static Regionizer<Bag> load(final int view, final Neighbourhood nb, final int dzIgnored, final int dz) {
        final Regionizer<Bag> r = new Regionizer<>(new RegionizerConfig(4, 2, false, nb), Bag::new, new Bag.Callbacks());
        final Set<Long> chunks = new HashSet<>();
        addWindow(chunks, 0, 0, view, false);
        if (dz != 0) {
            addWindow(chunks, 0, dz, view, false);
        }
        for (final long key : chunks) {
            r.addChunk((int) key * PER + 1, (int) (key >> 32) * PER + 1);
        }
        Harness.settle(r);
        return r;
    }

    private static Regionizer<Bag> loadDiag(final int view, final Neighbourhood nb, final int d) {
        final Regionizer<Bag> r = new Regionizer<>(new RegionizerConfig(4, 2, false, nb), Bag::new, new Bag.Callbacks());
        final Set<Long> chunks = new HashSet<>();
        addWindow(chunks, 0, 0, view, false);
        if (d != 0) {
            addWindow(chunks, d, d, view, false);
        }
        for (final long key : chunks) {
            r.addChunk((int) key * PER + 1, (int) (key >> 32) * PER + 1);
        }
        Harness.settle(r);
        return r;
    }

    private static void addWindow(final Set<Long> out, final int cx, final int cz, final int view, final boolean disc) {
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
