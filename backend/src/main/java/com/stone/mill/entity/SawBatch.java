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
 * 模块八：锯切批次（逐片产出谱系的根）。
 *
 * <p>受控流转：开锯建批 {@code OPEN} → 交接比对 {@code PENDING_RECONCILE}（有差异）/
 * 回到 {@code OPEN}（无差异且班组仍在交接）→ 差异全部核清 {@code READY} → 收口 {@code CLOSED}。
 * 收口后谱系计数（成品 / 余料 / 锯耗 / 合格实产）固化只读。
 *
 * <p>开锯即冻结：荒料、工单、锯切方式、班组、计划板数都复制成 snap/快照字段，
 * 排板顺序由 mill_saw_batch_item 快照保存；后续工单改锯切方式、排板重算都不改写本批。
 */
@Data
@Entity
@Table(name = "mill_saw_batch")
public class SawBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_no", nullable = false, length = 30)
    private String batchNo;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "block_id", nullable = false)
    private Long blockId;

    @Column(name = "snap_order_no", nullable = false, length = 30)
    private String snapOrderNo;

    @Column(name = "snap_block_code", nullable = false, length = 30)
    private String snapBlockCode;

    /** FRAME 框架锯 / DISC 圆盘锯 / WIRE 绳锯 / COMBO 组合锯（冻结） */
    @Column(name = "saw_type", nullable = false, length = 20)
    private String sawType;

    @Column(name = "team", length = 20)
    private String team;

    @Column(name = "plan_count", nullable = false)
    private Integer planCount;

    /** OPEN / PENDING_RECONCILE / READY / CLOSED */
    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "opened_by", length = 30)
    private String openedBy;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "handover_by", length = 30)
    private String handoverBy;

    @Column(name = "handover_at")
    private LocalDateTime handoverAt;

    @Column(name = "closed_by", length = 30)
    private String closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    // ---------------- 收口时固化的谱系计数（收口前保持 0，收口后只读） ----------------

    @Column(name = "finished_count", nullable = false)
    private Integer finishedCount = 0;

    @Column(name = "remnant_count", nullable = false)
    private Integer remnantCount = 0;

    @Column(name = "saw_loss_count", nullable = false)
    private Integer sawLossCount = 0;

    /** 合格实产：ACTIVE 的非次品成品片数，收口时据此推进工单完成数 */
    @Column(name = "qualified_count", nullable = false)
    private Integer qualifiedCount = 0;

    @Column(name = "remark", length = 200)
    private String remark;
}
