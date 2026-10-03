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
 * 模块十二：锯切批次 / 逐片产出的谱系留痕（只追加，不更新不删除）。
 *
 * <p>建批、逐片登记、交接、差异确认、归属更正（变更前后）、作废、收口都写一条；
 * 大板详情「历次更正」即按 slab 反查本批及后续批次中涉及该板的 audit 时间线。
 */
@Data
@Entity
@Table(name = "mill_saw_output_audit")
public class SawOutputAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "output_id")
    private Long outputId;

    /** CREATE_BATCH / REGISTER_OUTPUT / SUBMIT_HANDOVER / CONFIRM_DIFF / CORRECT_OUTPUT / VOID_OUTPUT / CLOSE_BATCH / RECOMPUTE */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    @Column(name = "from_content", length = 1000)
    private String fromContent;

    @Column(name = "to_content", length = 1000)
    private String toContent;

    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "operator", length = 30)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
