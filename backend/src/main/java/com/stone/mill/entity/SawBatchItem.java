package com.stone.mill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 批次排板顺序冻结快照：建批时从 mill_layout_item 复制。
 * 之后排板重算 / 大板资料变化都不改写这些行，作为交接比对（缺号 / 尺寸超差）的计划基准。
 */
@Data
@Entity
@Table(name = "mill_saw_batch_item")
public class SawBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Column(name = "slab_id")
    private Long slabId;

    @Column(name = "plan_width")
    private Integer planWidth;

    @Column(name = "plan_height")
    private Integer planHeight;

    @Column(name = "x_pos")
    private Integer xPos;

    @Column(name = "y_pos")
    private Integer yPos;
}
