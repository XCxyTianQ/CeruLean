package io.cerulean.regionizer;

import java.util.Collection;
import java.util.Map;

/**
 * 区域生命周期回调（对应 docs/06 §7 的状态挂载与迁移契约）。
 *
 * <p><b>契约</b>：所有回调都在区域化器的临界区内被调用，因此</p>
 * <ul>
 *   <li>不得阻塞、不得回调进区域化器（会死锁）；</li>
 *   <li>不得访问世界状态（第 09 课的结论：跨区域只能走投递通道）；</li>
 *   <li>{@link #mergeState} / {@link #splitState} <b>不得抛异常</b>（不变量 I9：此时已无法回退）。</li>
 * </ul>
 */
public interface RegionizerCallbacks<D> {

    /** 新区域已创建（此时还没有段）。 */
    default void onRegionCreate(final Region<D> region) {
    }

    /** 区域首次拥有段并进入可调度状态。 */
    default void onRegionActive(final Region<D> region) {
    }

    /** 区域段集清空（随后会被销毁或等待被合并）。 */
    default void onRegionDeactivate(final Region<D> region) {
    }

    /** 区域被销毁（合并或分裂导致的消失）。 */
    default void onRegionDestroy(final Region<D> region) {
    }

    default void onRegionPreMerge(final Region<D> from, final Region<D> into) {
    }

    default void onRegionPreSplit(final Region<D> from) {
    }

    /** 每个新区域创建完成、段已归位后调用一次。 */
    default void onRegionSplit(final Region<D> from, final Region<D> newRegion) {
    }

    /**
     * 状态迁移：合并。{@code from} 的数据要并入 {@code into}。
     *
     * @param tickOffset {@code into.tickCount - from.tickCount}：绝对 tick 截止时间必须平移该值
     */
    default void mergeState(final D from, final D into, final long tickOffset) {
    }

    /**
     * 状态迁移：分裂。区域化器已为每个新区域创建好空状态，并把
     * <b>所有存活段</b>的 key → 归属状态 放入 {@code sectionKeyToNewData}
     * （留在原区域的段映射到 {@code from} 自身，被迁走的映射到对应新状态）。
     * <p><b>表中不存在的段 = 该段已消亡（死段被回收）⇒ 它的状态必须丢弃。</b>
     * 实现按条目的段坐标查表：命中就搬（或留下），未命中就删除该条目。</p>
     */
    default void splitState(final D from, final int regionShift,
                            final Map<Long, D> sectionKeyToNewData, final Collection<D> newData) {
    }
}
