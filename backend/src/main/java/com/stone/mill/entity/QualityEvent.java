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
 * 模块七：质量隔离流转留痕。
 *
 * <p>只追加（append-only）：建立、送复检、每一次复检意见、放行、报废各落一条，
 * 应用层没有 update / delete 入口，最初缺陷与历次意见因此不会被覆盖。
 */
@Data
@Entity
@Table(name = "mill_quality_event")
public class QualityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hold_id", nullable = false)
    private Long holdId;

    /** CREATE / SUBMIT_RECHECK / RECHECK / RELEASE / SCRAP */
    @Column(name = "event_type", nullable = false, length = 25)
    private String eventType;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", length = 20)
    private String toStatus;

    /** PASS 合格放行 / REWORK 返工观察 / SCRAP 报废（仅复检类事件） */
    @Column(name = "conclusion", length = 20)
    private String conclusion;

    @Column(name = "content", length = 500)
    private String content;

    @Column(name = "operator", length = 30)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
