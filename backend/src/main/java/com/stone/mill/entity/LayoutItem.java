package com.stone.mill.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块四：排板方案 —— 一块荒料排板面上的板材矩形。
 *
 * <p>坐标系：x 沿荒料长度方向、y 沿荒料宽度方向，单位 cm；seq_no 为锯切顺序。
 */
@Data
@Entity
@Table(name = "mill_layout_item")
public class LayoutItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "block_id")
    private Long blockId;

    @Column(name = "slab_id")
    private Long slabId;

    @Column(name = "x_pos")
    private Integer xPos;

    @Column(name = "y_pos")
    private Integer yPos;

    @Column(name = "item_width")
    private Integer itemWidth;

    @Column(name = "item_height")
    private Integer itemHeight;

    @Column(name = "seq_no")
    private Integer seqNo;

    /** 该荒料排布损耗率（%） */
    @Column(name = "waste_rate", precision = 6, scale = 2)
    private BigDecimal wasteRate;
}
