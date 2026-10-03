package com.stone.mill.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块一：荒料。
 *
 * <p>体积 = 长×宽×高/1e6（m³），重量 = 体积 × 石种密度（吨），两者在登记时由服务层算好落库。
 */
@Data
@Entity
@Table(name = "mill_block")
public class Block {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "block_code", nullable = false, length = 30)
    private String blockCode;

    @Column(name = "quarry", length = 60)
    private String quarry;

    /** MARBLE 大理石 / GRANITE 花岗岩 / JADE 玉石 / QUARTZ 石英石 / TRAVERTINE 洞石 */
    @Column(name = "stone_type", length = 20)
    private String stoneType;

    @Column(name = "length_cm")
    private Integer lengthCm;

    @Column(name = "width_cm")
    private Integer widthCm;

    @Column(name = "height_cm")
    private Integer heightCm;

    @Column(name = "volume_m3", precision = 10, scale = 2)
    private BigDecimal volumeM3;

    @Column(name = "weight_ton", precision = 10, scale = 2)
    private BigDecimal weightTon;

    @Column(name = "inbound_date")
    private LocalDate inboundDate;

    /** 采购单价（元/m³） */
    @Column(name = "purchase_price", precision = 10, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "supplier", length = 80)
    private String supplier;

    /** PENDING 待排产 / PROCESSING 加工中 / DONE 已完料 / ARCHIVED 结库 */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "remark", length = 200)
    private String remark;
}
