package io.cerulean.regionizer.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import io.cerulean.regionizer.Regionizer;
import io.cerulean.regionizer.RegionizerConfig;

/**
 * 模型检验：对随机操作序列，逐步比对
 * <ol>
 *   <li>不变量（每次操作后、以及每次稳定化后）；</li>
 *   <li>稳定态下的区域划分 与 朴素参考实现（oracle）的规范划分；</li>
 *   <li>区块总数与 oracle 一致。</li>
 * </ol>
 * 覆盖多组配置（shift / mergeRadius）与多个随机种子。
 */
public final class ModelCheckTests {

    private ModelCheckTests() {
    }

    public static void run(final T t) {
        t.group("模型检验：随机操作序列 vs 朴素参考实现");
        final int[] shifts = {4, 2};
        final int[] radii = {1, 2, 3};
        final long[] seeds = {1L, 7L, 42L, 2026L, 99991L, 123456789L};
        final int ops = 150;

        for (final int shift : shifts) {
            for (final int radius : radii) {
                for (final long seed : seeds) {
                    trial(t, shift, radius, seed, ops);
                }
            }
        }
    }

    private static void trial(final T t, final int shift, final int radius, final long seed, final int ops) {
        final String name = "shift=" + shift + " mergeRadius=" + radius + " seed=" + seed;
        final Bag.Callbacks cb = new Bag.Callbacks();
        final Regionizer<Bag> riz = new Regionizer<>(new RegionizerConfig(shift, radius), Bag::new, cb);
        final ReferenceModel oracle = new ReferenceModel();
        final Random rnd = new Random(seed);
        final List<String> history = new ArrayList<>();

        final int span = 5;
        final int per = 1 << shift;

        for (int i = 0; i < ops; i++) {
            final int sx = rnd.nextInt(2 * span + 1) - span;
            final int sz = rnd.nextInt(2 * span + 1) - span;
            final int chunkX = sx * per + rnd.nextInt(per);
            final int chunkZ = sz * per + rnd.nextInt(per);
            final boolean add = rnd.nextBoolean();
            final String op = (add ? "add" : "remove") + "(section " + sx + "," + sz + ')';
            history.add(op);

            if (add) {
                riz.addChunk(chunkX, chunkZ);
                oracle.addChunk(chunkX, chunkZ, shift);
            } else {
                riz.removeChunk(chunkX, chunkZ);
                oracle.removeChunk(chunkX, chunkZ, shift);
            }

            List<String> v = riz.verifyInvariants();
            if (!v.isEmpty()) {
                t.check(name + " [操作后不变量 @" + i + " " + op + ']', false,
                        Harness.describe(v) + " | 历史: " + String.join(", ", history));
                return;
            }

            Harness.settle(riz);

            v = riz.verifyInvariants(true);
            if (!v.isEmpty()) {
                t.check(name + " [稳定后不变量 @" + i + " " + op + ']', false,
                        Harness.describe(v) + " | 历史: " + String.join(", ", history));
                return;
            }

            final Set<Set<Long>> got = Harness.partitionOf(riz);
            final Set<Set<Long>> want = oracle.partition(radius);
            if (!got.equals(want)) {
                t.check(name + " [划分 @操作 " + i + ' ' + op + ']', false,
                        "实现=" + got + " oracle=" + want + " | 历史: " + String.join(", ", history));
                return;
            }
            if (riz.chunkCount() != oracle.totalChunks()) {
                t.check(name + " [区块总数 @" + i + ']', false,
                        riz.chunkCount() + " vs " + oracle.totalChunks());
                return;
            }
        }
        t.check(name + " (" + ops + " 步随机操作)", true);
    }
}
