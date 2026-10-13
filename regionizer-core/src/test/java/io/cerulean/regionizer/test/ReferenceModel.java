package io.cerulean.regionizer.test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import io.cerulean.regionizer.SectionKey;

/**
 * 朴素参考实现（oracle）：每次调用都<b>全量重算</b>规范划分，不依赖任何增量逻辑。
 *
 * <p>它是被测实现必须在"稳定态"收敛到的判据（docs/06 语义 S1/S2）：</p>
 * <ol>
 *   <li>段存活 ⟺ 自身有区块，或有四邻之一有区块（一级定义）；</li>
 *   <li>区域 = 存活段在 Chebyshev ≤ mergeRadius 邻接下的连通分量。</li>
 * </ol>
 */
public final class ReferenceModel {

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** 存在的段 → 区块数（计数可为 0；不存在则不在表里）。 */
    private final Map<Long, Integer> countBySection = new HashMap<>();

    public void addChunk(final int chunkX, final int chunkZ, final int shift) {
        final long key = SectionKey.pack(SectionKey.chunkToSection(chunkX, shift), SectionKey.chunkToSection(chunkZ, shift));
        this.countBySection.merge(key, 1, Integer::sum);
    }

    public void removeChunk(final int chunkX, final int chunkZ, final int shift) {
        final long key = SectionKey.pack(SectionKey.chunkToSection(chunkX, shift), SectionKey.chunkToSection(chunkZ, shift));
        final Integer cur = this.countBySection.get(key);
        if (cur == null || cur == 0) {
            return;
        }
        this.countBySection.put(key, cur - 1);
    }

    public int totalChunks() {
        int n = 0;
        for (final int c : this.countBySection.values()) {
            n += c;
        }
        return n;
    }

    /** 闭式存活集合：有区块的段 ∪ 这些段的四邻（无状态、可直接重算）。 */
    public Set<Long> aliveKeys() {
        final Set<Long> alive = new LinkedHashSet<>();
        for (final Map.Entry<Long, Integer> e : this.countBySection.entrySet()) {
            if (e.getValue() > 0) {
                alive.add(e.getKey());
            }
        }
        for (final Map.Entry<Long, Integer> e : this.countBySection.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            final int x = SectionKey.unpackX(e.getKey());
            final int z = SectionKey.unpackZ(e.getKey());
            for (final int[] d : CARDINALS) {
                alive.add(SectionKey.pack(x + d[0], z + d[1]));
            }
        }
        return alive;
    }

    /** 规范划分（与顺序无关的"集合的集合"）。 */
    public Set<Set<Long>> partition(final int mergeRadius) {
        final Set<Long> aliveKeys = this.aliveKeys();
        final Set<Long> seen = new HashSet<>();
        final Set<Set<Long>> out = new HashSet<>();
        for (final long start : aliveKeys) {
            if (!seen.add(start)) {
                continue;
            }
            final Set<Long> comp = new HashSet<>();
            comp.add(start);
            final ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                final long cur = queue.poll();
                final int cx = SectionKey.unpackX(cur);
                final int cz = SectionKey.unpackZ(cur);
                for (int dx = -mergeRadius; dx <= mergeRadius; dx++) {
                    for (int dz = -mergeRadius; dz <= mergeRadius; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        final long nk = SectionKey.pack(cx + dx, cz + dz);
                        if (aliveKeys.contains(nk) && seen.add(nk)) {
                            comp.add(nk);
                            queue.add(nk);
                        }
                    }
                }
            }
            out.add(comp);
        }
        return out;
    }
}
