package com.stone.mill.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * 手写 Criteria API 的动态查询条件（同时作为 Controller 的 @ModelAttribute 入参）。
 *
 * <p>字段为 null / 空即表示「不参与拼接」，Repository 按此逐条拼 Predicate。
 */
public final class Queries {

    private Queries() {
    }

    /** 分页基类：offset/limit 直接给 Criteria 的 setFirstResult / setMaxResults 用。 */
    @Data
    public static class BaseQuery {

        private int page = 1;
        private int size = 20;

        public int offset() {
            int p = page < 1 ? 1 : page;
            int s = size < 1 ? 20 : size;
            return (p - 1) * s;
        }

        public int limit() {
            return size < 1 ? 20 : size;
        }
    }

    /** 荒料筛选：石种 / 状态 / 矿口 / 体积区间 / 长宽下限 / 编号模糊 / 排序 / 分页 */
    @Data
    public static class BlockQuery extends BaseQuery {

        private String stoneType;
        private String status;
        private String quarry;
        private String keyword;
        private BigDecimal minVolume;
        private BigDecimal maxVolume;
        private Integer minLength;
        private Integer minWidth;
        private LocalDate inboundFrom;
        private LocalDate inboundTo;
        /** code / volume / weight / date */
        private String orderBy = "code";
        private boolean desc = false;
    }

    /** 大板筛选：石种（子查询落到荒料）/ 等级 / 状态 / 表面处理 / 面积区间 / 长宽下限 / 编号模糊 */
    @Data
    public static class SlabQuery extends BaseQuery {

        private String stoneType;
        private String grade;
        private String status;
        private String finish;
        private String keyword;
        private BigDecimal minArea;
        private BigDecimal maxArea;
        private Integer minLength;
        private Integer minWidth;
        private Long blockId;
        /** area / length / code / grade */
        private String orderBy = "area";
        private boolean desc = true;
    }

    /** 可发货大板筛选：只取在库 / 已预定，再按等级、面积、长宽、编号收敛 */
    @Data
    public static class ShippableQuery extends BaseQuery {

        private List<String> statuses;
        private List<String> grades;
        private BigDecimal minArea;
        private BigDecimal maxArea;
        private Integer minLength;
        private Integer minWidth;
        private String keyword;
        /** area / length / code */
        private String orderBy = "area";
        private boolean desc = true;
    }

    /** 加工工单筛选 */
    @Data
    public static class OrderQuery extends BaseQuery {

        private Long blockId;
        private String sawType;
        private String status;
        private String team;
        private String keyword;
        /** date / plan / progress / code */
        private String orderBy = "date";
        private boolean desc = true;
    }

    /** 发货单筛选 */
    @Data
    public static class DeliveryQuery extends BaseQuery {

        private String status;
        private String keyword;
        private LocalDate dateFrom;
        private LocalDate dateTo;
        private BigDecimal minAmount;
        /** date / amount / code */
        private String orderBy = "date";
        private boolean desc = true;
    }

    /** 质量隔离记录筛选：记录状态 / 缺陷类型 / 严重程度 / 荒料编号 / 大板编号 */
    @Data
    public static class QualityHoldQuery extends BaseQuery {

        private String status;
        private String defectType;
        private String severity;
        private String blockCode;
        private String slabCode;
        /** time / code / severity */
        private String orderBy = "time";
        private boolean desc = true;
    }

    /** 锯切批次筛选：批次状态 / 工单 / 荒料 / 班组 / 批次号或工单号模糊 */
    @Data
    public static class SawBatchQuery extends BaseQuery {

        private String status;
        private Long orderId;
        private Long blockId;
        private String team;
        private String keyword;
        /** time / code */
        private String orderBy = "time";
        private boolean desc = true;
    }
}
