package io.cerulean.regions;

import io.papermc.paper.configuration.ConfigurationPart;

import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.PostProcess;

/**
 * CeruLean 区域化参数（挂在 {@code config/paper-global.yml} 下）。
 *
 * <p>做法照上游：Folia 也是往 Paper 的全局配置里加一节，并用 {@code @PostProcess}
 * 在配置加载后把参数推给区域化内核（此时 worlds 尚未加载，因此每个 world 拿到的都是同一份有效参数）。</p>
 */
public class CeruLeanGlobalConfig extends ConfigurationPart {

    @Comment("段大小的位移：4 ⇒ 每段 16×16 区块。与上游 Folia 的 grid-exponent 对齐。")
    public int gridExponent = 4;

    @Comment(
        "合并半径（单位：段）。同一区域覆盖范围 ≈ (视距+1) 区块 + 本值 × 段大小。\n"
        + "上游 Folia 取 1（=16 区块）；本项目默认 2（=32 区块），即在“大型机械会不会被区域边界切开”\n"
        + "这一维度上比上游更保守。实测：本值每 +1，两玩家分区分界约 +1 区块。"
    )
    public int mergeRadius = 2;

    @Comment(
        "邻居语义（物化范围与存活判定范围必须一致，否则段会“生下来就是死的”）：\n"
        + "SQUARE_3X3 = 3×3 方形（上游语义，默认）；CARDINAL_4 = 4 正邻（稀疏布局少维护约 44% 的段）；SQUARE_5X5。\n"
        + "实测：连续移动下两者区域事件数相同；分区分界相差约 1 区块。"
    )
    public String neighbourhood = "SQUARE_3X3";

    @Comment("是否在进入 TICKING 时复制段集快照（用于不变量校验；生产环境建议 false）。")
    public boolean trackTickingSections = false;

    @PostProcess
    private void postProcess() {
        CeruLeanRegions.init(this);
    }
}
