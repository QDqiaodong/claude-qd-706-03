package com.stone.mill.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块十一：计划 vs 实产差异（差异待核清单的一行）。
 *
 * <p>交接时比对冻结排板与逐片实产生成：缺号 / 重复号 / 尺寸超差 / 数量不符 / 未解释锯耗。
 * 现场记录不丢弃：差异进入 OPEN「差异待核」，批次在全部差异核清前不能收口，
 * 未核清期间也不增加工单完成数。
 *
 * <p>核清只有两种方式：{@code CONFIRMED} 负责人确认实际结果；{@code CORRECTED} 更正归属/作废。
 * 两种方式都必须填 {@link #reason}，变更前后另存 mill_saw_output_audit。
 */
@Data
@Entity
@Table(name = "mill_saw_discrepancy")
public class SawDiscrepancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "output_id")
    private Long outputId;

    /** MISSING_SEQ / DUP_SEQ / SIZE_TOLERANCE / COUNT_MISMATCH / UNEXPLAINED_SAW_LOSS */
    @Column(name = "diff_type", nullable = false, length = 24)
    private String diffType;

    @Column(name = "seq_no")
    private Integer seqNo;

    @Column(name = "detail", nullable = false, length = 500)
    private String detail;

    /** OPEN / CONFIRMED / CORRECTED */
    @Column(name = "status", nullable = false, length = 12)
    private String status = "OPEN";

    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "resolved_by", length = 30)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
