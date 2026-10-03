package com.stone.mill.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 五个模块的入参。 */
public final class Dtos {

    private Dtos() {
    }

    // ---------------- 荒料 ----------------

    @Data
    public static class BlockSaveReq {

        @NotBlank(message = "荒料编号不能为空")
        private String blockCode;

        private String quarry;

        @NotBlank(message = "石种不能为空")
        private String stoneType;

        @NotNull(message = "长不能为空")
        private Integer lengthCm;

        @NotNull(message = "宽不能为空")
        private Integer widthCm;

        @NotNull(message = "高不能为空")
        private Integer heightCm;

        private LocalDate inboundDate;
        private BigDecimal purchasePrice;
        private String supplier;
        private String remark;
    }

    @Data
    public static class StatusReq {

        @NotBlank(message = "状态不能为空")
        private String status;

        private String remark;
    }

    // ---------------- 大板 ----------------

    @Data
    public static class SlabSaveReq {

        @NotBlank(message = "大板编号不能为空")
        private String slabCode;

        @NotNull(message = "所属荒料不能为空")
        private Long blockId;

        @NotNull(message = "长不能为空")
        private Integer lengthCm;

        @NotNull(message = "宽不能为空")
        private Integer widthCm;

        /** 可空：默认 2.0 cm */
        private BigDecimal thicknessCm;

        @NotBlank(message = "表面处理不能为空")
        private String finish;

        @NotBlank(message = "质量等级不能为空")
        private String grade;

        private String location;
        private String remark;
    }

    // ---------------- 加工工单 ----------------

    @Data
    public static class OrderSaveReq {

        @NotBlank(message = "工单号不能为空")
        private String orderNo;

        @NotNull(message = "荒料不能为空")
        private Long blockId;

        @NotBlank(message = "锯切方式不能为空")
        private String sawType;

        @NotNull(message = "计划板数不能为空")
        private Integer planCount;

        private String team;
        private LocalDate startDate;
        private String remark;
    }

    @Data
    public static class ProgressReq {

        /** 本次完成的板数 */
        private Integer delta = 1;
    }

    // ---------------- 排板 ----------------

    @Data
    public static class RelayoutReq {

        /** 参与重排的大板 id，留空表示取该荒料全部大板 */
        private List<Long> slabIds;
    }

    // ---------------- 出库发货 ----------------

    @Data
    public static class DeliverySaveReq {

        @NotBlank(message = "发货单号不能为空")
        private String deliveryNo;

        @NotBlank(message = "客户不能为空")
        private String customer;

        private String contact;
        private String phone;
        private String plateNo;
        private String driver;
        private LocalDate deliveryDate;
        private String remark;
    }

    @Data
    public static class DeliveryItemReq {

        @NotNull(message = "大板不能为空")
        private Long slabId;

        /** 可空：默认 1 片 */
        private Integer quantity;

        @NotNull(message = "单价不能为空")
        private BigDecimal unitPrice;
    }

    // ---------------- 质量隔离 ----------------

    @Data
    public static class QualityHoldCreateReq {

        @NotNull(message = "大板不能为空")
        private Long slabId;

        @NotBlank(message = "缺陷类型不能为空")
        private String defectType;

        @NotBlank(message = "严重程度不能为空")
        private String severity;

        private String affectPosition;

        /** 发现环节可空（客户事后补录时可能填客户反馈） */
        private String foundStage;

        private String siteNote;

        private String disposeAdvice;

        private String reporter;
    }

    /** 送复检：仅追加意见，状态 隔离中 → 待复检 */
    @Data
    public static class SubmitRecheckReq {

        private String note;

        private String operator;
    }

    /**
     * 复检结论（追加留痕，不覆盖最初缺陷与历次意见）：
     * PASS 合格放行（待复检→已放行，解除发货挂起）；
     * REWORK 返工观察（状态留在待复检，只追加一条复检意见）；
     * SCRAP 报废（待复检→已报废，从未发运发货单移除明细并重算金额）。
     */
    @Data
    public static class RecheckReq {

        @NotBlank(message = "复检结论不能为空：PASS / REWORK / SCRAP")
        private String conclusion;

        private String opinion;

        private String operator;
    }

    // ---------------- 锯切批次交接与产出谱系 ----------------

    /** 开锯建批：冻结工单 / 荒料 / 锯切方式 / 当时排板顺序 */
    @Data
    public static class SawBatchCreateReq {

        @NotNull(message = "加工工单不能为空")
        private Long orderId;

        private String openedBy;

        private String remark;

        /**
         * 本批覆盖的排板顺序号；为空则冻结该荒料当前全部排板顺序。
         * 同一块荒料可分多批开锯（如 1~4 序 / 5~8 序）。
         */
        private List<Integer> seqNos;
    }

    /**
     * 逐片产出登记。
     * outputType=FINISHED/REMNANT 时按 slabId（既有大板）或 slabCode+尺寸（新切大板）落产出；
     * outputType=SAW_LOSS 时尺寸/大板留空，lossNote 解释锯耗（不填即未解释锯耗，待核）。
     * clientToken 是现场幂等键：断网重试 / 重复扫码同键只返回首次产出。
     */
    @Data
    public static class SawOutputRegisterReq {

        private Integer seqNo;

        @NotBlank(message = "产出类型不能为空：FINISHED / REMNANT / SAW_LOSS")
        private String outputType;

        /** 已存在大板 id（选用既有大板时给） */
        private Long slabId;

        /** 新切出的大板编号（slabId 为空时按编号 + 尺寸建大板） */
        private String slabCode;

        private Integer lengthCm;
        private Integer widthCm;
        private BigDecimal thicknessCm;

        /** A / B / C / DEFECT，默认 A */
        private String grade;

        /** 表面处理；为空时默认 HONED 亚光 */
        private String finish;

        /** 可返切余料的来源大板 */
        private Long sourceSlabId;

        /** 锯耗说明（SAW_LOSS 不填即未解释锯耗） */
        private String lossNote;

        @NotBlank(message = "现场扫码幂等键不能为空（断网重试靠它去重）")
        private String clientToken;

        private String operator;
    }

    /** 交接提交：班组先完成逐片交接，系统比对计划/实产生成差异 */
    @Data
    public static class SawHandoverReq {

        private String handoverBy;
    }

    /**
     * 差异核清：
     * action=CONFIRM 确认实际结果（必须填 reason）；
     * action=CORRECT_ASSIGN 把某条产出更正归属到目标批次/顺序号（必须填 reason）；
     * action=CORRECT_VOID 作废本批某条错误产出（必须填 reason）。
     */
    @Data
    public static class SawDiffResolveReq {

        @NotBlank(message = "核清方式不能为空：CONFIRM / CORRECT_ASSIGN / CORRECT_VOID")
        private String action;

        @NotBlank(message = "核清原因不能为空（确认实际结果 / 更正归属都要留原因）")
        private String reason;

        private String operator;

        /** CORRECT_ASSIGN：目标批次 */
        private Long targetBatchId;

        /** CORRECT_ASSIGN：更正后的顺序号（可空，如余料） */
        private Integer targetSeqNo;

        /** CORRECT_VOID：要作废的产出（缺省取差异关联产出） */
        private Long outputId;
    }

    /** 收口：差异全部核清后一次性固化谱系并按合格实产推进工单 */
    @Data
    public static class SawCloseReq {

        private String closedBy;
    }
}
