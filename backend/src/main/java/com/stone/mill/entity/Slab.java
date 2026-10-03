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
 * 模块二：大板（荒料锯切后的成品板）。
 *
 * <p>面积 = 长×宽/1e4（m²）。所属荒料只存 block_id，石种这类跨表条件走 Criteria 子查询。
 */
@Data
@Entity
@Table(name = "mill_slab")
public class Slab {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slab_code", nullable = false, length = 30)
    private String slabCode;

    @Column(name = "block_id")
    private Long blockId;

    @Column(name = "length_cm")
    private Integer lengthCm;

    @Column(name = "width_cm")
    private Integer widthCm;

    @Column(name = "thickness_cm", precision = 5, scale = 1)
    private BigDecimal thicknessCm;

    @Column(name = "area_m2", precision = 10, scale = 2)
    private BigDecimal areaM2;

    /** POLISHED 光面 / HONED 亚光 / LITCHI 荔枝面 / FLAMED 火烧面 / LEATHER 皮革面 */
    @Column(name = "finish", length = 20)
    private String finish;

    /** A / B / C / DEFECT 次品 */
    @Column(name = "grade", length = 10)
    private String grade;

    @Column(name = "location", length = 30)
    private String location;

    /** INSTOCK 在库 / RESERVED 已预定 / OUTBOUND 已出库 / SCRAP 报废 */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "remark", length = 200)
    private String remark;
}
