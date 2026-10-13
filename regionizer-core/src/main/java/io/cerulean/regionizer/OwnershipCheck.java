package io.cerulean.regionizer;

/**
 * 归属判定（B2）：回答"这个线程能不能碰这个位置"。
 *
 * <p><b>为什么单独成类</b>：这是纯函数式的策略层，不依赖任何服务端类型，因此可以
 * 在 {@code regionizer-core} 里被完整单测（含"软/硬"两种模式），
 * 而服务端侧只留一层极薄的适配（{@code TickThread} 的重载 → 本类）。</p>
 *
 * <p><b>判定规则</b>（按顺序）：</p>
 * <ol>
 *   <li>该位置没有区域，或区域不在 TICKING ⇒ <b>允许</b>（{@code NO_OWNER}）——无人拥有即谁都能碰；</li>
 *   <li>区域在 TICKING 且 {@code owner == 本线程} ⇒ <b>允许</b>（{@code OWNER}）；</li>
 *   <li>区域在 TICKING 且本线程是<b>主线程</b>，且策略不禁止主线程 ⇒ <b>记录为"将会违规"但允许</b>
 *       （{@code MAIN_THREAD_OVERRIDE}）——这是过渡期的"软模式"：它把"如果开严格模式会被拒绝"的
 *       调用点全部报出来，用来生成 B5 的工作清单；
 *   <li>其余 ⇒ <b>拒绝</b>（{@code FOREIGN_THREAD}）。</li>
 * </ol>
 *
 * <p><b>为什么主线程要特殊对待</b>：在 B5 把"必须主线程"的那几步（FULL 装载、FullChunkStatus 切换、
 * 卸载、mid-tick 泵）改成"必须拥有该区域的线程"之前，主线程仍在合法地做这些事。
 * 所以严格模式必须先于 B5 落地，否则一开就崩——这正是"谨慎"的含义。</p>
 */
public final class OwnershipCheck {

    /** 结果。只有 {@link #DENIED} 会真正抛错（严格模式）。 */
    public enum Verdict {
        /** 该位置无拥有者（无区域 / 区域非 TICKING / 未绑定线程）→ 允许。 */
        NO_OWNER,
        /** 本线程就是拥有者 → 允许。 */
        OWNER,
        /** 本线程是主线程，但该位置正被别的线程 tick → 软模式下允许并记录（严格模式下会拒绝）。 */
        MAIN_THREAD_OVERRIDE,
        /** 本线程不是拥有者且不是主线程 → 拒绝。 */
        FOREIGN_THREAD,
        /** 明确拒绝（严格模式下主线程的覆盖也被关掉时使用）。 */
        DENIED
    }

    /** 处理策略。 */
    public enum Policy {
        /** 关闭（默认）：永远允许——与 Paper 原行为完全一致。 */
        OFF,
        /** 软模式：允许一切，但把"将会违规"的情况报给回调（用于收集工作清单）。 */
        SOFT,
        /** 严格模式：除拥有者与无拥有者外一律拒绝（B5 之后的目标态）。 */
        STRICT
    }

    /** "将会违规"的观察者（软模式用）。实现必须便宜、不得抛异常、不得回调回区域化器。 */
    public interface Listener {
        void onWouldViolate(Verdict verdict, int chunkX, int chunkZ, long currentThreadId, long ownerThreadId);
    }

    private OwnershipCheck() {
    }

    /**
     * 判定一次访问。
     *
     * @param regionizer     目标世界的区域化器
     * @param chunkX/chunkZ  区块坐标
     * @param currentThreadId 当前线程 id
     * @param mainThreadId   主线程 id（-1 表示未知 ⇒ 不做主线程特殊化）
     * @param policy         策略
     * @param listener       软模式的观察者（可为 null）
     */
    public static Verdict check(final Regionizer<?> regionizer, final int chunkX, final int chunkZ,
                                final long currentThreadId, final long mainThreadId,
                                final Policy policy, final Listener listener) {
        if (policy == Policy.OFF) {
            return Verdict.NO_OWNER;          // 关闭时短路：热路径只需读一个枚举
        }
        final long owner = regionizer.ownerThreadIdAt(chunkX, chunkZ);
        if (owner < 0L) {
            return Verdict.NO_OWNER;
        }
        if (owner == currentThreadId) {
            return Verdict.OWNER;
        }
        final boolean isMainThread = mainThreadId >= 0L && currentThreadId == mainThreadId;
        if (isMainThread && policy == Policy.SOFT) {
            if (listener != null) {
                listener.onWouldViolate(Verdict.MAIN_THREAD_OVERRIDE, chunkX, chunkZ, currentThreadId, owner);
            }
            return Verdict.MAIN_THREAD_OVERRIDE;
        }
        if (isMainThread) {
            if (listener != null) {
                listener.onWouldViolate(Verdict.DENIED, chunkX, chunkZ, currentThreadId, owner);
            }
            return Verdict.DENIED;
        }
        if (listener != null) {
            listener.onWouldViolate(Verdict.FOREIGN_THREAD, chunkX, chunkZ, currentThreadId, owner);
        }
        return Verdict.FOREIGN_THREAD;
    }

    /** 该结果是否允许通过。 */
    public static boolean allows(final Verdict verdict) {
        return verdict == Verdict.NO_OWNER || verdict == Verdict.OWNER
                || verdict == Verdict.MAIN_THREAD_OVERRIDE;
    }
}
