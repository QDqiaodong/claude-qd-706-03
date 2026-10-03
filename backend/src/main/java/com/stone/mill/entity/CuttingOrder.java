package com.stone.mill.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 模块三：加工工单（一块荒料的一次锯切任务）。 */
@Data
@Entity
@Table(name = "mill_cutting_order")
public class CuttingOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 30)
    private String orderNo;

    @Column(name = "block_id")
    private Long blockId;

    /** FRAME 框架锯 / DISC 圆盘锯 / WIRE 绳锯 / COMBO 组合锯 */
    @Column(name = "saw_type", length = 20)
    private String sawType;

    @Column(name = "plan_count")
    private Integer planCount;

    @Column(name = "done_count")
    private Integer doneCount;

    /**
     * 锯切批次基线完成数：
     * null 表示批次功能上线前的旧工单（仍按原手工方式登记完成数，页面标「待补交接」）；
     * 非 null 表示已纳入锯切批次交接，首个批次建立时固化当时的 doneCount，
     * 此后 doneCount = sawBaselineCount + Σ 已收口批次的合格实产，不能再手工 +1。
     */
    @Column(name = "saw_baseline_count")
    private Integer sawBaselineCount;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "finish_date")
    private LocalDate finishDate;

    @Column(name = "team", length = 20)
    private String team;

    /** PENDING 待排产 / PROCESSING 加工中 / DONE 已完工 / SETTLED 已结算 */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "remark", length = 200)
    private String remark;
}
