package io.cerulean.regionizer.test;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import io.cerulean.regionizer.Region;
import io.cerulean.regionizer.RegionizerCallbacks;

/**
 * 带"按段存储"状态的测试数据：用来验证 docs/06 §7 的迁移契约
 * （合并要平移 tick 偏移；分裂要按坐标分流）。
 */
public final class Bag {

    /** 段 key → 该段上的状态条目。 */
    public final Map<Long, String> entries = new LinkedHashMap<>();

    /** 合并回调收到的 tick 偏移，供断言使用。 */
    public static long lastMergeTickOffset = Long.MIN_VALUE;

    public static final class Callbacks implements RegionizerCallbacks<Bag> {

        public int creates;
        public int actives;
        public int deactivates;
        public int destroys;
        public int preMerges;
        public int preSplits;
        public int splits;

        /** 已销毁区域（用于断言"销毁后数据被清空"）。 */
        public final java.util.List<Region<Bag>> destroyed = new java.util.ArrayList<>();

        @Override
        public void onRegionCreate(final Region<Bag> region) {
            this.creates++;
        }

        @Override
        public void onRegionActive(final Region<Bag> region) {
            this.actives++;
        }

        @Override
        public void onRegionDeactivate(final Region<Bag> region) {
            this.deactivates++;
        }

        @Override
        public void onRegionDestroy(final Region<Bag> region) {
            this.destroys++;
            this.destroyed.add(region);
        }

        @Override
        public void onRegionPreMerge(final Region<Bag> from, final Region<Bag> into) {
            this.preMerges++;
        }

        @Override
        public void onRegionPreSplit(final Region<Bag> from) {
            this.preSplits++;
        }

        @Override
        public void onRegionSplit(final Region<Bag> from, final Region<Bag> newRegion) {
            this.splits++;
        }

        @Override
        public void mergeState(final Bag from, final Bag into, final long tickOffset) {
            Bag.lastMergeTickOffset = tickOffset;
            into.entries.putAll(from.entries);
        }

        @Override
        public void splitState(final Bag from, final int regionShift,
                               final Map<Long, Bag> sectionKeyToNewData, final Collection<Bag> newData) {
            final Iterator<Map.Entry<Long, String>> it = from.entries.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<Long, String> e = it.next();
                final Bag target = sectionKeyToNewData.get(e.getKey());
                if (target == null) {
                    it.remove();                      // 段已消亡 → 丢弃状态
                } else if (target != from) {
                    target.entries.put(e.getKey(), e.getValue());
                    it.remove();
                }                                       // == from：留在原区域，不动
            }
        }
    }

    public Bag() {
    }
}
