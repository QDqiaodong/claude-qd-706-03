package com.stone.mill.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 石材加工厂词典：石种、表面处理、质量等级、各类状态与 SVG 着色。
 *
 * <p>规则集中在此，便于出题时被要求调整或扩展。
 */
public final class StoneDict {

    private StoneDict() {
    }

    // ---------------- 石种 ----------------
    private static final Map<String, String> STONE_TYPE = Map.of(
            "MARBLE", "大理石",
            "GRANITE", "花岗岩",
            "JADE", "玉石",
            "QUARTZ", "石英石",
            "TRAVERTINE", "洞石");

    /** 石种密度（吨/m³），用于由体积反算重量 */
    private static final Map<String, BigDecimal> STONE_DENSITY = Map.of(
            "MARBLE", BigDecimal.valueOf(2.70),
            "GRANITE", BigDecimal.valueOf(2.80),
            "JADE", BigDecimal.valueOf(2.90),
            "QUARTZ", BigDecimal.valueOf(2.60),
            "TRAVERTINE", BigDecimal.valueOf(2.50));

    /** 排板 SVG 的底色（按石种） */
    private static final Map<String, String> STONE_COLOR = Map.of(
            "MARBLE", "#eceff1",
            "GRANITE", "#e3e8ea",
            "JADE", "#e0f0e6",
            "QUARTZ", "#e8eaee",
            "TRAVERTINE", "#f0ebe3");

    // ---------------- 表面处理 ----------------
    private static final Map<String, String> FINISH = Map.of(
            "POLISHED", "光面",
            "HONED", "亚光",
            "LITCHI", "荔枝面",
            "FLAMED", "火烧面",
            "LEATHER", "皮革面");

    // ---------------- 质量等级 ----------------
    private static final Map<String, String> GRADE = Map.of(
            "A", "A 级",
            "B", "B 级",
            "C", "C 级",
            "DEFECT", "次品");

    /** 等级着色：A 绿 / B 黄 / C 灰 / 次品红 */
    private static final Map<String, String> GRADE_COLOR = Map.of(
            "A", "#43a047",
            "B", "#f9a825",
            "C", "#90a4ae",
            "DEFECT", "#e53935");

    // ---------------- 荒料状态 ----------------
    private static final Map<String, String> BLOCK_STATUS = Map.of(
            "PENDING", "待排产",
            "PROCESSING", "加工中",
            "DONE", "已完料",
            "ARCHIVED", "结库");

    // ---------------- 大板状态 ----------------
    private static final Map<String, String> SLAB_STATUS = Map.of(
            "INSTOCK", "在库",
            "RESERVED", "已预定",
            "OUTBOUND", "已出库",
            "SCRAP", "报废");

    /** 可发货的大板状态（在库 / 已预定） */
    public static final java.util.List<String> SHIPPABLE_STATUS = java.util.List.of("INSTOCK", "RESERVED");

    // ---------------- 锯切方式 ----------------
    private static final Map<String, String> SAW_TYPE = Map.of(
            "FRAME", "框架锯",
            "DISC", "圆盘锯",
            "WIRE", "绳锯",
            "COMBO", "组合锯");

    // ---------------- 工单状态 ----------------
    private static final Map<String, String> ORDER_STATUS = Map.of(
            "PENDING", "待排产",
            "PROCESSING", "加工中",
            "DONE", "已完工",
            "SETTLED", "已结算");

    // ---------------- 发货状态 ----------------
    private static final Map<String, String> DELIVERY_STATUS = Map.of(
            "LOADING", "待装车",
            "SHIPPED", "已发运",
            "SIGNED", "已签收",
            "PAID", "已结款");

    // ---------------- 质量隔离状态 ----------------
    private static final Map<String, String> HOLD_STATUS = Map.of(
            "ISOLATED", "隔离中",
            "PENDING_RECHECK", "待复检",
            "RELEASED", "已放行",
            "SCRAPPED", "已报废");

    /** 未收口（仍在冻结发货）的隔离状态 */
    public static final java.util.List<String> HOLD_ACTIVE_STATUS =
            java.util.List.of("ISOLATED", "PENDING_RECHECK");

    // ---------------- 缺陷类型 ----------------
    private static final Map<String, String> DEFECT_TYPE = Map.of(
            "CHIPPED", "崩边",
            "CRACK", "裂纹",
            "COLOR_DIFF", "色差",
            "HOLE", "孔洞",
            "SCRATCH", "划伤",
            "OTHER", "其他");

    // ---------------- 严重程度 ----------------
    private static final Map<String, String> SEVERITY = Map.of(
            "MINOR", "轻微",
            "MAJOR", "较重",
            "CRITICAL", "严重");

    // ---------------- 发现环节 ----------------
    private static final Map<String, String> FOUND_STAGE = Map.of(
            "CUTTING", "锯切",
            "GRINDING", "打磨",
            "POLISHING", "抛光",
            "INSPECTION", "成检",
            "WAREHOUSE", "入库",
            "LOADING", "装车前",
            "FEEDBACK", "客户反馈");

    // ---------------- 隔离流转事件 ----------------
    private static final Map<String, String> HOLD_EVENT = Map.of(
            "CREATE", "建立隔离",
            "SUBMIT_RECHECK", "送复检",
            "RECHECK", "复检意见",
            "RELEASE", "复检放行",
            "SCRAP", "判定报废");

    // ---------------- 复检结论 ----------------
    private static final Map<String, String> RECONCILE_RESULT = Map.of(
            "PASS", "合格放行",
            "REWORK", "返工观察",
            "SCRAP", "报废");

    // ---------------- 锯切批次 ----------------
    private static final Map<String, String> SAW_BATCH_STATUS = Map.of(
            "OPEN", "开锯交接中",
            "PENDING_RECONCILE", "差异待核",
            "READY", "可收口",
            "CLOSED", "已收口");

    /** 未收口（仍在交接 / 待核 / 待收口）的批次状态 */
    public static final java.util.List<String> SAW_BATCH_OPEN_STATUS =
            java.util.List.of("OPEN", "PENDING_RECONCILE", "READY");

    // ---------------- 逐片产出类型 ----------------
    private static final Map<String, String> SAW_OUTPUT_TYPE = Map.of(
            "FINISHED", "成品",
            "REMNANT", "可返切余料",
            "SAW_LOSS", "锯耗");

    // ---------------- 逐片产出状态 ----------------
    private static final Map<String, String> SAW_OUTPUT_STATUS = Map.of(
            "ACTIVE", "有效",
            "VOID", "已作废");

    // ---------------- 计划实产差异类型 ----------------
    private static final Map<String, String> SAW_DIFF_TYPE = Map.of(
            "MISSING_SEQ", "缺号",
            "DUP_SEQ", "重复号",
            "SIZE_TOLERANCE", "尺寸超差",
            "COUNT_MISMATCH", "数量不符",
            "UNEXPLAINED_SAW_LOSS", "未解释锯耗");

    // ---------------- 差异核清状态 ----------------
    private static final Map<String, String> SAW_DIFF_STATUS = Map.of(
            "OPEN", "差异待核",
            "CONFIRMED", "已确认",
            "CORRECTED", "已更正");

    // ---------------- 谱系留痕事件 ----------------
    private static final Map<String, String> SAW_AUDIT_EVENT = Map.of(
            "CREATE_BATCH", "开锯建批",
            "REGISTER_OUTPUT", "逐片登记",
            "SUBMIT_HANDOVER", "交接提交",
            "CONFIRM_DIFF", "确认实际结果",
            "CORRECT_OUTPUT", "归属更正",
            "VOID_OUTPUT", "作废产出",
            "CLOSE_BATCH", "批次收口",
            "RECOMPUTE", "差异重算");

    /** 尺寸允差（cm）：实际长/宽与冻结计划偏差超过该值即「尺寸超差」 */
    public static final int SIZE_TOLERANCE_CM = 3;

    // ---------------- 取值 ----------------

    public static boolean isValidStoneType(String v) {
        return v != null && STONE_TYPE.containsKey(v);
    }

    public static String stoneTypeName(String v) {
        return v == null ? "" : STONE_TYPE.getOrDefault(v, v);
    }

    public static BigDecimal stoneDensity(String v) {
        return STONE_DENSITY.getOrDefault(v, BigDecimal.valueOf(2.70));
    }

    public static String stoneColor(String v) {
        return STONE_COLOR.getOrDefault(v, "#eceff1");
    }

    public static boolean isValidFinish(String v) {
        return v != null && FINISH.containsKey(v);
    }

    public static String finishName(String v) {
        return v == null ? "" : FINISH.getOrDefault(v, v);
    }

    public static boolean isValidGrade(String v) {
        return v != null && GRADE.containsKey(v);
    }

    public static String gradeName(String v) {
        return v == null ? "" : GRADE.getOrDefault(v, v);
    }

    public static String gradeColor(String v) {
        return GRADE_COLOR.getOrDefault(v, "#90a4ae");
    }

    public static boolean isValidBlockStatus(String v) {
        return v != null && BLOCK_STATUS.containsKey(v);
    }

    public static String blockStatusName(String v) {
        return v == null ? "" : BLOCK_STATUS.getOrDefault(v, v);
    }

    public static boolean isValidSlabStatus(String v) {
        return v != null && SLAB_STATUS.containsKey(v);
    }

    public static String slabStatusName(String v) {
        return v == null ? "" : SLAB_STATUS.getOrDefault(v, v);
    }

    public static boolean isValidSawType(String v) {
        return v != null && SAW_TYPE.containsKey(v);
    }

    public static String sawTypeName(String v) {
        return v == null ? "" : SAW_TYPE.getOrDefault(v, v);
    }

    public static boolean isValidOrderStatus(String v) {
        return v != null && ORDER_STATUS.containsKey(v);
    }

    public static String orderStatusName(String v) {
        return v == null ? "" : ORDER_STATUS.getOrDefault(v, v);
    }

    public static boolean isValidDeliveryStatus(String v) {
        return v != null && DELIVERY_STATUS.containsKey(v);
    }

    public static String deliveryStatusName(String v) {
        return v == null ? "" : DELIVERY_STATUS.getOrDefault(v, v);
    }

    public static boolean isValidHoldStatus(String v) {
        return v != null && HOLD_STATUS.containsKey(v);
    }

    public static String holdStatusName(String v) {
        return v == null ? "" : HOLD_STATUS.getOrDefault(v, v);
    }

    public static boolean isHoldActive(String v) {
        return v != null && HOLD_ACTIVE_STATUS.contains(v);
    }

    public static boolean isValidDefectType(String v) {
        return v != null && DEFECT_TYPE.containsKey(v);
    }

    public static String defectTypeName(String v) {
        return v == null ? "" : DEFECT_TYPE.getOrDefault(v, v);
    }

    public static boolean isValidSeverity(String v) {
        return v != null && SEVERITY.containsKey(v);
    }

    public static String severityName(String v) {
        return v == null ? "" : SEVERITY.getOrDefault(v, v);
    }

    public static boolean isValidFoundStage(String v) {
        return v != null && FOUND_STAGE.containsKey(v);
    }

    public static String foundStageName(String v) {
        return v == null ? "" : FOUND_STAGE.getOrDefault(v, v);
    }

    public static String holdEventName(String v) {
        return v == null ? "" : HOLD_EVENT.getOrDefault(v, v);
    }

    public static boolean isValidReconcileResult(String v) {
        return v != null && RECONCILE_RESULT.containsKey(v);
    }

    public static String reconcileResultName(String v) {
        return v == null ? "" : RECONCILE_RESULT.getOrDefault(v, v);
    }

    public static boolean isValidSawBatchStatus(String v) {
        return v != null && SAW_BATCH_STATUS.containsKey(v);
    }

    public static String sawBatchStatusName(String v) {
        return v == null ? "" : SAW_BATCH_STATUS.getOrDefault(v, v);
    }

    public static boolean isSawBatchOpen(String v) {
        return v != null && SAW_BATCH_OPEN_STATUS.contains(v);
    }

    public static boolean isValidOutputType(String v) {
        return v != null && SAW_OUTPUT_TYPE.containsKey(v);
    }

    public static String outputTypeName(String v) {
        return v == null ? "" : SAW_OUTPUT_TYPE.getOrDefault(v, v);
    }

    public static String outputStatusName(String v) {
        return v == null ? "" : SAW_OUTPUT_STATUS.getOrDefault(v, v);
    }

    public static boolean isValidDiffType(String v) {
        return v != null && SAW_DIFF_TYPE.containsKey(v);
    }

    public static String diffTypeName(String v) {
        return v == null ? "" : SAW_DIFF_TYPE.getOrDefault(v, v);
    }

    public static String diffStatusName(String v) {
        return v == null ? "" : SAW_DIFF_STATUS.getOrDefault(v, v);
    }

    public static String sawAuditEventName(String v) {
        return v == null ? "" : SAW_AUDIT_EVENT.getOrDefault(v, v);
    }
}
