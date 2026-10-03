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

/** 模块五：出库发货单。总金额由明细金额汇总后落库。 */
@Data
@Entity
@Table(name = "mill_delivery")
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "delivery_no", nullable = false, length = 30)
    private String deliveryNo;

    @Column(name = "customer", length = 80)
    private String customer;

    @Column(name = "contact", length = 30)
    private String contact;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "plate_no", length = 20)
    private String plateNo;

    @Column(name = "driver", length = 30)
    private String driver;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** LOADING 待装车 / SHIPPED 已发运 / SIGNED 已签收 / PAID 已结款 */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "remark", length = 200)
    private String remark;
}
