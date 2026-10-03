package com.stone.mill.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 模块五：发货大板明细。金额 = 数量 × 面积 × 单价。 */
@Data
@Entity
@Table(name = "mill_delivery_item")
public class DeliveryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_id")
    private Long deliveryId;

    @Column(name = "slab_id")
    private Long slabId;

    @Column(name = "slab_code", length = 30)
    private String slabCode;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "area_m2", precision = 10, scale = 2)
    private BigDecimal areaM2;

    /** 单价（元/m²） */
    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "amount", precision = 12, scale = 2)
    private BigDecimal amount;
}
