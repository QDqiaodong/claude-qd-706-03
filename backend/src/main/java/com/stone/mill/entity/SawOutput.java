package com.stone.mill.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块十：逐片产出登记（成品 / 可返切余料 / 锯耗回到同一批次核对）。
 *
 * <p>两道唯一约束：
 * <ol>
 *   <li>{@code (batch_id, client_token)}：现场断网重试 / 重复扫码带同一幂等键时，
 *       服务端只返回首次产出，绝不生成两份；</li>
 *   <li>{@code active_slab_id}（生成列唯一索引）：同一块大板全库只能有一条 ACTIVE 产出。
 *       跨批次重复扫码 / 两个批次并发登记同一大板时只有一条成功，后到者读到先到归属，
 *       由 service 转成 409 冲突回带当前归属，不能静默覆盖。</li>
 * </ol>
 *
 * <p>{@code status=VOID} 表示归属更正 / 差异作废：记录保留（谱系与审计需要），
 * 唯一归属随 active_slab_id 变为 NULL 而释放。
 */
@Data
@Entity
@Table(name = "mill_saw_output")
public class SawOutput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "slab_id")
    private Long slabId;

    /** 可返切余料的来源大板（返切谱系；作废时检查是否被后续批次引用） */
    @Column(name = "source_slab_id")
    private Long sourceSlabId;

    /** 对应排板顺序号；余料 / 锯耗可空 */
    @Column(name = "seq_no")
    private Integer seqNo;

    /** FINISHED 成品 / REMNANT 可返切余料 / SAW_LOSS 锯耗 */
    @Column(name = "output_type", nullable = false, length = 12)
    private String outputType;

    @Column(name = "actual_length")
    private Integer actualLength;

    @Column(name = "actual_width")
    private Integer actualWidth;

    @Column(name = "actual_thickness", precision = 5, scale = 1)
    private BigDecimal actualThickness;

    /** A / B / C / DEFECT；DEFECT 成品不计合格实产 */
    @Column(name = "grade", length = 10)
    private String grade;

    @Column(name = "finish", length = 20)
    private String finish;

    /** 锯耗说明：为空即「未解释锯耗」，交接时进入差异待核 */
    @Column(name = "loss_note", length = 200)
    private String lossNote;

    /** ACTIVE / VOID */
    @Column(name = "status", nullable = false, length = 10)
    private String status = "ACTIVE";

    /** 现场幂等键（扫码设备生成的去重号） */
    @Column(name = "client_token", nullable = false, length = 64)
    private String clientToken;

    @Column(name = "operator", length = 30)
    private String operator;

    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 数据库生成列：ACTIVE 且挂大板时 = slabId，否则 NULL。应用只读不写。 */
    @Column(name = "active_slab_id", insertable = false, updatable = false)
    private Long activeSlabId;
}
