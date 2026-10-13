package io.cerulean.regionizer;

/**
 * 邻居语义：**物化范围与存活判定范围必须是同一个邻域**。
 *
 * <p>这一点是从上游源码里读出来的硬约束（`study/folia` 还原源码 L317 create 用 Chebyshev 方形、
 * L1160 存活判据的上界就是该方形大小、L578 `mergeRadius = max(mergeRadius, createRadius)`）。
 * 若只改其中一个，段会"生下来就是死的"——这正是把两个参数绑成一个枚举的原因。</p>
 */
public enum Neighbourhood {

    /** 4 个正邻居（上/下/左/右）。本项目 C1 起的默认值：稀疏布局下维护的段最少。 */
    CARDINAL_4(manhattanOffsets(1)),

    /** 3×3 方形（含 4 个对角）。等价上游 `createRadius = 1`：多一圈"护城河"。 */
    SQUARE_3X3(chebyshevOffsets(1)),

    /** 5×5 方形。等价上游 `createRadius = 2`。 */
    SQUARE_5X5(chebyshevOffsets(2));

    private final int[][] offsets;

    Neighbourhood(final int[][] offsets) {
        this.offsets = offsets;
    }

    /** 不含自身的邻居偏移。 */
    public int[][] offsets() {
        return this.offsets;
    }

    /** 邻居数量（用于 I5 断言的上界与文档）。 */
    public int count() {
        return this.offsets.length;
    }

    private static int[][] manhattanOffsets(final int r) {
        final java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                final int d = Math.abs(dx) + Math.abs(dz);
                if (d != 0 && d <= r) {
                    out.add(new int[]{dx, dz});
                }
            }
        }
        return out.toArray(new int[0][]);
    }

    private static int[][] chebyshevOffsets(final int r) {
        final java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx != 0 || dz != 0) {
                    out.add(new int[]{dx, dz});
                }
            }
        }
        return out.toArray(new int[0][]);
    }
}
