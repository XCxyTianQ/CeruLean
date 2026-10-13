package io.cerulean.regionizer.test;

import java.util.List;

import io.cerulean.regionizer.Region;
import io.cerulean.regionizer.Regionizer;
import io.cerulean.regionizer.RegionizerConfig;

/**
 * C3 的"优化前"基线微基准（纯 JDK，无第三方）。
 *
 * 度量三件事：
 *   1. addChunk 吞吐（含挂载/收敛扫描）
 *   2. **释放点成本**（tryMarkTicking + markNotTicking）——即 A1 阈值化要优化的那条路径
 *      （当前实现每次释放都精确回收死段 + 重算连通分量，代价 O(段数 × (2r+1)²)）
 *   3. getRegionAt 吞吐（A4 乐观读要优化的读路径）
 *
 * 用法: java ... Benchmark [边长] [释放次数]
 */
public final class Benchmark {

    private Benchmark() {
    }

    public static void main(final String[] args) {
        final int side = args.length > 0 ? Integer.parseInt(args[0]) : 48;   // 段为单位的边长
        final int releases = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        final int shift = 4;
        final int per = 1 << shift;

        System.out.printf("== regionizer 基线微基准（段边长 %d → 约 %d 段；释放 %d 次）%n", side, side * side, releases);

        final Regionizer<Bag> riz = new Regionizer<>(new RegionizerConfig(shift, 2), Bag::new, new Bag.Callbacks());

        // ---- 1) addChunk：每个段放一个区块，构成一大片连通的区域 ----
        final long t0 = System.nanoTime();
        int ops = 0;
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                riz.addChunk(x * per + 1, z * per + 1);
                ops++;
            }
        }
        final long addNanos = System.nanoTime() - t0;
        final List<Region<Bag>> regions = riz.regionsSnapshot();
        System.out.printf("  addChunk      : %d 次 / %.1f ms  → %.2f µs/次（%.0f 次/秒）%n",
                ops, addNanos / 1e6, addNanos / 1e3 / ops, ops / (addNanos / 1e9));
        System.out.printf("  构建结果      : 区域 %d 个 / 段 %d（存活 %d）/ 区块 %d%n",
                regions.size(), riz.sectionCount(), riz.aliveSectionCount(), riz.chunkCount());

        // ---- 2) 释放点成本：对所有区域各做一次"取 tick → 释放"，重复 N 轮 ----
        final int regionCount = regions.size();
        final long t1 = System.nanoTime();
        int releaseCount = 0;
        for (int i = 0; i < releases; i++) {
            for (final Region<Bag> region : riz.regionsSnapshot()) {
                if (riz.tryMarkTicking(region)) {
                    riz.markNotTicking(region);
                    releaseCount++;
                }
            }
        }
        final long relNanos = System.nanoTime() - t1;
        System.out.printf("  释放点        : %d 次 / %.1f ms  → %.1f µs/次（每轮 %d 个区域）%n",
                releaseCount, relNanos / 1e6, relNanos / 1e3 / releaseCount, regionCount);
        System.out.printf("  即每次 tick 的拓扑维护成本 ≈ %.2f ms（区域 %d 个 × %.1f µs）%n",
                relNanos / 1e6 / releases, regionCount, relNanos / 1e3 / releaseCount);

        // ---- 3) getRegionAt 读路径 ----
        final int reads = 200_000;
        final java.util.Random rnd = new java.util.Random(42);
        long sink = 0;
        final long t2 = System.nanoTime();
        for (int i = 0; i < reads; i++) {
            final Region<Bag> r = riz.getRegionAt(rnd.nextInt(side * per), rnd.nextInt(side * per));
            if (r != null) {
                sink += r.id();
            }
        }
        final long readNanos = System.nanoTime() - t2;
        System.out.printf("  getRegionAt   : %d 次 / %.1f ms  → %.3f µs/次（%.1f 万次/秒）[sink=%d]%n",
                reads, readNanos / 1e6, readNanos / 1e3 / reads, reads / (readNanos / 1e9) / 1e4, sink);

        // ---- 4) removeChunk（不改拓扑，应当很便宜） ----
        final long t3 = System.nanoTime();
        int rem = 0;
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                if (riz.removeChunk(x * per + 1, z * per + 1)) {
                    rem++;
                }
            }
        }
        final long remNanos = System.nanoTime() - t3;
        System.out.printf("  removeChunk   : %d 次 / %.1f ms  → %.2f µs/次（不应改变拓扑；释放后段数=%d）%n",
                rem, remNanos / 1e6, remNanos / 1e3 / Math.max(1, rem), riz.sectionCount());

        final long t4 = System.nanoTime();
        for (final Region<Bag> region : riz.regionsSnapshot()) {
            if (riz.tryMarkTicking(region)) {
                riz.markNotTicking(region);
            }
        }
        System.out.printf("  清空后释放一次: %.1f ms → 区域 %d / 段 %d / 区块 %d%n",
                (System.nanoTime() - t4) / 1e6, riz.regionCount(), riz.sectionCount(), riz.chunkCount());
    }
}
