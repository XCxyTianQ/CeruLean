package io.cerulean.regionizer.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.cerulean.regionizer.Region;
import io.cerulean.regionizer.Regionizer;

/** 测试公共工具：稳定化（把每个区域都走一遍"取 tick → 释放"）与划分提取。 */
public final class Harness {

    private Harness() {
    }

    /**
     * 稳定化：反复让每个区域经历"tryMarkTicking → markNotTicking"，直到一轮下来没有任何区域
     * 还能被 tick。这正是"稳定态"的定义（每个区域都经过释放点）。
     */
    public static <D> void settle(final Regionizer<D> regionizer) {
        String prev = signature(regionizer);
        for (int pass = 0; pass < 32; pass++) {
            for (final Region<D> region : regionizer.regionsSnapshot()) {
                if (regionizer.tryMarkTicking(region)) {
                    regionizer.markNotTicking(region);
                }
            }
            final String now = signature(regionizer);
            if (now.equals(prev)) {
                return;                       // 一整轮没有任何结构性变化 ⇒ 已稳定
            }
            prev = now;
        }
        throw new IllegalStateException("稳定化未在 32 轮内收敛");
    }

    /** 结构签名：区域数 / 段数 / 区块数 / 每个区域的 (id,段数,区块数,状态)。 */
    private static <D> String signature(final Regionizer<D> regionizer) {
        final List<String> parts = new ArrayList<>();
        for (final Region<D> region : regionizer.regionsSnapshot()) {
            parts.add(region.id() + "/" + region.sectionCount() + "/" + region.chunkCount() + "/" + region.state());
        }
        java.util.Collections.sort(parts);
        return regionizer.regionCount() + "|" + regionizer.sectionCount() + "|" + regionizer.chunkCount()
                + "|" + String.join(",", parts);
    }

    /** 被测实现当前的划分（每个区域一组段 key）。 */
    public static <D> Set<Set<Long>> partitionOf(final Regionizer<D> regionizer) {
        final Set<Set<Long>> out = new HashSet<>();
        for (final Region<D> region : regionizer.regionsSnapshot()) {
            out.add(new HashSet<>(region.sectionKeys()));
        }
        return out;
    }

    /** 把违规列表格式化成一行，便于断言输出。 */
    public static String describe(final List<String> violations) {
        return violations.isEmpty() ? "-" : String.join(" | ", violations.subList(0, Math.min(3, violations.size())));
    }

    public static List<String> copy(final List<String> in) {
        return new ArrayList<>(in);
    }
}
