package com.stone.mill.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 模块六：大板质量隔离记录。
 *
 * <p>受控流转：隔离中 ISOLATED → 待复检 PENDING_RECHECK → 已放行 RELEASED / 已报废 SCRAPPED。
 *
 * <p>建立时把大板编号、所属荒料、等级、库位、大板状态复制成快照（snap_*），
 * 后续基础资料变化不改写这些字段，作为当时的质量证据。
 *
 * <p>{@code activeSlabId} 是库里的生成列（未收口时 = slab_id，收口后 NULL），
 * 配合部分唯一索引保证同一大板同一时刻只有一条未收口记录；这里设为只读 insertable=false。
 * 并发复检用 {@link Version} 乐观锁，重复提交只有一条生效。
 */
@Data
@Entity
@Table(name = "mill_quality_hold")
public class QualityHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hold_no", nullable = false, length = 30)
    private String holdNo;

    @Column(name = "slab_id", nullable = false)
    private Long slabId;

    /** 第几轮隔离：放行后再次发现问题新开一轮，前一轮保留 */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** CHIPPED 崩边 / CRACK 裂纹 / COLOR_DIFF 色差 / HOLE 孔洞 / SCRATCH 划伤 / OTHER 其他 */
    @Column(name = "defect_type", nullable = false, length = 20)
    private String defectType;

    /** MINOR 轻微 / MAJOR 较重 / CRITICAL 严重 */
    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    @Column(name = "affect_position", length = 100)
    private String affectPosition;

    /** CUTTING 锯切 / GRINDING 打磨 / POLISHING 抛光 / INSPECTION 成检 / WAREHOUSE 入库 / LOADING 装车前 / FEEDBACK 客户反馈 */
    @Column(name = "found_stage", length = 20)
    private String foundStage;

    @Column(name = "site_note", length = 500)
    private String siteNote;

    @Column(name = "dispose_advice", length = 500)
    private String disposeAdvice;

    @Column(name = "reporter", length = 30)
    private String reporter;

    /** ISOLATED / PENDING_RECHECK / RELEASED / SCRAPPED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // ---------------- 建立时的基础资料快照（不可变证据） ----------------

    @Column(name = "snap_slab_code", nullable = false, length = 30)
    private String snapSlabCode;

    @Column(name = "snap_block_code", length = 30)
    private String snapBlockCode;

    @Column(name = "snap_block_id")
    private Long snapBlockId;

    @Column(name = "snap_grade", length = 10)
    private String snapGrade;

    @Column(name = "snap_location", length = 30)
    private String snapLocation;

    @Column(name = "snap_slab_status", length = 20)
    private String snapSlabStatus;

    // ---------------- 收口信息 ----------------

    @Column(name = "closed_remark", length = 500)
    private String closedRemark;

    @Column(name = "closed_by", length = 30)
    private String closedBy;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** 乐观锁：并发复检 / 重复点击只有一个生效 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 数据库生成列，应用只读不写 */
    @Column(name = "active_slab_id", insertable = false, updatable = false)
    private Long activeSlabId;
}
