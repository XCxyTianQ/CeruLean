package io.cerulean.regionizer.test;

import java.util.HashSet;
import java.util.Set;

import io.cerulean.regionizer.Neighbourhood;
import io.cerulean.regionizer.Regionizer;
import io.cerulean.regionizer.RegionizerConfig;

/**
 * 移动抖动实验：玩家沿**对角线**移动时，两种邻居语义各自产生多少拓扑事件。
 *
 * <p>为什么要沿对角移动：4 正邻与 3×3 方形唯一的差别就在**对角方向**，
 * 对角移动是该差别的最坏用例。</p>
 *
 * <p>统计的是"玩家能感知到的成本"：区域创建/销毁/合并/分裂（每次都会引发状态迁移），
 * 以及段的物化/回收（内存与 map 操作）。</p>
 *
 * <p>加载形状两种都测：方形（原版真实行为：Moonrise `ViewDistances` 按区块数的 Chebyshev 半径）
 * 与圆形（实体可见性的形状，也是很多人对"加载范围"的直觉）。</p>
 */
public final class MovementChurn {

    private MovementChurn() {
    }

    public static void main(final String[] args) {
        final int steps = 200;
        for (final String path : new String[]{"multiplayer"}) {
            for (final boolean disc : path.equals("scatter") ? new boolean[]{false} : new boolean[]{false, true}) {
                System.out.printf("%n== 路径=%s 加载形状=%s（%d 步）%n", path, disc ? "圆" : "方", steps);
                System.out.printf("%-11s %8s %8s %8s %8s %8s %8s %8s %8s%n",
                        "邻居语义", "段物化", "段回收", "区域创建", "区域销毁", "合并", "分裂", "事件合计", "峰值段");
                for (final Neighbourhood nb : new Neighbourhood[]{Neighbourhood.CARDINAL_4, Neighbourhood.SQUARE_3X3}) {
                    for (final int view : new int[]{4, 10, 16}) {
                        run(view, steps, nb, disc, path);
                    }
                }
            }
        }
    }

    private static void run(final int view, final int steps, final Neighbourhood nb, final boolean disc,
                            final String path) {
        final Bag.Callbacks cb = new Bag.Callbacks();
        final Regionizer<Bag> riz = new Regionizer<>(
                new RegionizerConfig(4, 2, false, nb), Bag::new, cb);
        Set<Long> loaded = new HashSet<>();
        long created = 0;
        int centerX = 0;
        int centerZ = 0;
        final int[] pX = new int[4];
        final int[] pZ = new int[4];
        long reclaimed = 0;
        int prevSections = 0;
        int peak = 0;

        for (int step = 0; step < steps; step++) {
            final Set<Long> next = new HashSet<>();
            if (path.equals("multiplayer")) {
                // 最接近真实服务器：4 个玩家各自缓慢游走（每步移动 -1/0/+1），彼此相距很远
                for (int i = 0; i < 4; i++) {
                    final java.util.Random rnd = new java.util.Random(step * 17L + i * 1013L);
                    pX[i] += rnd.nextInt(3) - 1;
                    pZ[i] += rnd.nextInt(3) - 1;
                    final int cx = pX[i] + i * 200;
                    final int cz = pZ[i] + i * 200;
                    for (int dx = -view; dx <= view; dx++) {
                        for (int dz = -view; dz <= view; dz++) {
                            if (disc && dx * dx + dz * dz > view * view) {
                                continue;
                            }
                            next.add(pack(cx + dx, cz + dz));
                        }
                    }
                }
            } else if (path.equals("scatter")) {
                // 探索式零散加载：在窗口内随机选取点集（每次变化一部分），最接近"玩家四处跑图"
                final java.util.Random rnd = new java.util.Random(step * 31L + view);
                for (int i = 0; i < 4; i++) {
                    final int cx = step + rnd.nextInt(64) - 32;
                    final int cz = step + rnd.nextInt(64) - 32;
                    for (int dx = -view; dx <= view; dx++) {
                        for (int dz = -view; dz <= view; dz++) {
                            if (dx * dx + dz * dz <= view * view) {
                                next.add(pack(cx + dx, cz + dz));
                            }
                        }
                    }
                }
            } else {
                if (path.equals("diagonal")) {
                    centerX = step;
                    centerZ = step;
                } else {
                    final java.util.Random rnd = new java.util.Random(step * 7L);
                    centerX += rnd.nextInt(3) - 1;
                    centerZ += rnd.nextInt(3) - 1;
                }
                for (int dx = -view; dx <= view; dx++) {
                    for (int dz = -view; dz <= view; dz++) {
                        if (disc && dx * dx + dz * dz > view * view) {
                            continue;
                        }
                        next.add(pack(centerX + dx, centerZ + dz));
                    }
                }
            }
            for (final long key : next) {
                if (!loaded.contains(key)) {
                    riz.addChunk((int) key * 16 + 1, (int) (key >> 32) * 16 + 1);
                }
            }
            for (final long key : loaded) {
                if (!next.contains(key)) {
                    riz.removeChunk((int) key * 16 + 1, (int) (key >> 32) * 16 + 1);
                }
            }
            loaded = next;
            Harness.settle(riz);
            final int sections = riz.sectionCount();
            if (sections > prevSections) {
                created += sections - prevSections;
            } else {
                reclaimed += prevSections - sections;
            }
            prevSections = sections;
            peak = Math.max(peak, sections);
        }
        final long events = cb.creates + cb.destroys + cb.preMerges + cb.splits;
        System.out.printf("%-11s %8d %8d %8d %8d %8d %8d %8d %8d%n",
                nb + "(视距" + view + ")", created, reclaimed, cb.creates, cb.destroys, cb.preMerges,
                cb.splits, events, peak);
    }

    private static long pack(final int x, final int z) {
        return (x & 0xFFFFFFFFL) | ((long) z << 32);
    }
}
