package io.cerulean.regionizer;

/**
 * 区域状态机（见 docs/06 区域化设计规格 §5）。
 *
 * <pre>
 *   TRANSIENT（出生 / 有挂起合并，不可 tick）
 *       │ 合并握手清空且段集稳定
 *       ▼
 *     READY ──tryMarkTicking──▶ TICKING ──markNotTicking（释放点）──▶ READY
 *       │                                                              │
 *       └──────────────── 段集被清空 / 被并入其他区域 ────────────────▶ DEAD
 * </pre>
 */
public enum RegionState {
    /** 刚创建，或仍有挂起的合并握手：不可 tick。 */
    TRANSIENT((byte) 0),
    /** 可以 tick（段集与区块集在本状态下视为稳定）。 */
    READY((byte) 1),
    /** 正在 tick：**段集与区块集不可变**（不变量 I2）。 */
    TICKING((byte) 2),
    /** 已并入其他区域或已销毁。 */
    DEAD((byte) 3);

    public final byte id;

    RegionState(final byte id) {
        this.id = id;
    }
}
