-- ============================================================================
-- 石材加工厂（大板加工）生产管理系统 · 建表 + 种子数据
-- 库名 stone_mill，由 mysql 镜像的 docker-entrypoint 在【镜像构建期】执行一次。
--
-- 七个业务模块：
--   1. mill_block        荒料（矿口产地 / 石种 / 长宽高 / 体积 / 重量 / 采购单价 / 状态）
--   2. mill_slab         大板（所属荒料 / 长宽厚 / 面积 / 表面处理 / 质量等级 / 库位 / 状态）
--   3. mill_cutting_order 加工工单（锯切方式 / 计划板数 / 已完成板数 / 班组 / 状态）
--   4. mill_layout_item  排板方案（荒料上的 x/y/宽/高 + 顺序号 + 损耗率）
--   5. mill_delivery / mill_delivery_item 出库发货（客户 / 车牌 / 大板明细 / 金额 / 状态）
--   6. mill_quality_hold 大板质量隔离记录（缺陷 / 严重度 / 基础资料快照 / 受控流转状态）
--   7. mill_quality_event 质量隔离留痕（建立 / 送复检 / 历次复检意见 / 放行 / 报废，只追加）
--   8. mill_saw_batch     锯切批次（开锯冻结荒料/工单/锯切方式/排板顺序，交接-待核-收口受控流转）
--   9. mill_saw_batch_item 批次排板顺序冻结快照（建立时复制，后续重排不改写）
--  10. mill_saw_output    逐片产出登记（成品/可返切余料/锯耗，幂等键 + 大板唯一归属兜底）
--  11. mill_saw_discrepancy 计划 vs 实产差异（缺号/重复号/尺寸超差/数量不符/未解释锯耗，待核阻断收口）
--  12. mill_saw_output_audit 批次与产出历次更正留痕（建立/交接/登记/归属更正/作废/确认/收口，只追加）
--
-- 锯切批次口径：
--   * 开锯建批即冻结：荒料、工单、锯切方式、计划板数与当时的排板顺序（mill_saw_batch_item 快照）；
--   * 逐片登记带现场幂等键 client_token（断网重试 / 重复扫码只返回首次产出，绝不生成两份）；
--     同一块大板全库只能有一条 ACTIVE 产出，由生成列 active_slab_id 的唯一索引兜底跨批次并发；
--   * 交接时比对计划与实产生成差异，批次进入「差异待核」：差异未核清不能收口，
--     未经核对的差异不增加工单完成数；确认 / 更正必须填原因，变更前后写入 audit；
--   * 收口在单事务内悲观锁串行：固化谱系计数并按合格实产回写工单完成数，
--     两人同时收口 / 重复点击 / 中途失败都只能形成一个有效结果（后到者 409 回带已收口结果）；
--   * mill_cutting_order.saw_baseline_count 为 NULL 表示该工单是批次功能上线前的旧工单
--     （仍按原手工方式查看，页面标「待补交接」）；首个批次建立时固化当时完成数为基线，
--     此后完成数 = 基线 + 各【已收口】批次合格实产，未收口批次不计入。
--
-- 质量隔离口径：
--   * 同一块大板同一时刻只允许一条未收口（隔离中 / 待复检）记录，由部分唯一索引
--     uk_hold_active_slab（生成列 active_slab_id）在数据库层兜底并发重复发起；
--   * 隔离记录冻结当时的大板编号 / 荒料编号 / 等级 / 库位 / 状态快照，基础资料后续变化不改写；
--   * 复检结论只往 mill_quality_event 追加，不覆盖最初缺陷与历次意见；
--   * 隔离中 / 待复检即对发货派生冻结：大板不能再选入发货单，已在待装车单中的明细挂起禁运；
--     放行由活跃记录消失自动解除挂起，报废则从待装车单移除明细并重算总金额。
--
-- 派生口径：体积 = 长×宽×高/1e6；重量 = 体积×石种密度（大理石 2.7 / 花岗岩 2.8 /
-- 玉石 2.9 / 石英石 2.6 / 洞石 2.5）；面积 = 长×宽/1e4；明细金额 = 数量×面积×单价；
-- 损耗率 =（荒料排板面 - 已排板材面积）/ 排板面。
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS mill_saw_output_audit;
DROP TABLE IF EXISTS mill_saw_discrepancy;
DROP TABLE IF EXISTS mill_saw_output;
DROP TABLE IF EXISTS mill_saw_batch_item;
DROP TABLE IF EXISTS mill_saw_batch;
DROP TABLE IF EXISTS mill_quality_event;
DROP TABLE IF EXISTS mill_quality_hold;
DROP TABLE IF EXISTS mill_seq;
DROP TABLE IF EXISTS mill_delivery_item;
DROP TABLE IF EXISTS mill_delivery;
DROP TABLE IF EXISTS mill_layout_item;
DROP TABLE IF EXISTS mill_cutting_order;
DROP TABLE IF EXISTS mill_slab;
DROP TABLE IF EXISTS mill_block;

CREATE TABLE mill_block (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    block_code     VARCHAR(30)   NOT NULL COMMENT '荒料编号',
    quarry         VARCHAR(60)            COMMENT '矿口产地',
    stone_type     VARCHAR(20)            COMMENT '石种：MARBLE 大理石 / GRANITE 花岗岩 / JADE 玉石 / QUARTZ 石英石 / TRAVERTINE 洞石',
    length_cm      INT                    COMMENT '长（cm）',
    width_cm       INT                    COMMENT '宽（cm）',
    height_cm      INT                    COMMENT '高（cm）',
    volume_m3      DECIMAL(10,2)          COMMENT '体积（m³）= 长×宽×高/1e6',
    weight_ton     DECIMAL(10,2)          COMMENT '重量（吨）= 体积×石种密度',
    inbound_date   DATE                   COMMENT '进厂日期',
    purchase_price DECIMAL(10,2)          COMMENT '采购单价（元/m³）',
    supplier       VARCHAR(80)            COMMENT '供应商',
    status         VARCHAR(20)            COMMENT 'PENDING 待排产 / PROCESSING 加工中 / DONE 已完料 / ARCHIVED 结库',
    remark         VARCHAR(200)           COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_block_code (block_code),
    KEY idx_block_type (stone_type),
    KEY idx_block_status (status),
    KEY idx_block_inbound (inbound_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='荒料';

CREATE TABLE mill_slab (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    slab_code    VARCHAR(30)  NOT NULL COMMENT '大板编号',
    block_id     BIGINT                COMMENT '所属荒料',
    length_cm    INT                   COMMENT '长（cm）',
    width_cm     INT                   COMMENT '宽（cm）',
    thickness_cm DECIMAL(5,1)          COMMENT '厚度（cm）',
    area_m2      DECIMAL(10,2)         COMMENT '面积（m²）= 长×宽/1e4',
    finish       VARCHAR(20)           COMMENT '表面处理：POLISHED 光面 / HONED 亚光 / LITCHI 荔枝面 / FLAMED 火烧面 / LEATHER 皮革面',
    grade        VARCHAR(10)           COMMENT '质量等级：A / B / C / DEFECT 次品',
    location     VARCHAR(30)           COMMENT '库位',
    status       VARCHAR(20)           COMMENT 'INSTOCK 在库 / RESERVED 已预定 / OUTBOUND 已出库 / SCRAP 报废',
    remark       VARCHAR(200)          COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_slab_code (slab_code),
    KEY idx_slab_block (block_id),
    KEY idx_slab_grade (grade),
    KEY idx_slab_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='大板';

CREATE TABLE mill_cutting_order (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    order_no   VARCHAR(30) NOT NULL COMMENT '工单号',
    block_id   BIGINT               COMMENT '荒料',
    saw_type   VARCHAR(20)          COMMENT '锯切方式：FRAME 框架锯 / DISC 圆盘锯 / WIRE 绳锯 / COMBO 组合锯',
    plan_count INT                  COMMENT '计划板数',
    done_count INT                  COMMENT '已完成板数（基线 + 已收口批次合格实产；旧工单仍为手工登记值）',
    saw_baseline_count INT          COMMENT '锯切批次基线完成数：NULL=批次功能上线前的旧工单（待补交接）；首个批次建立时固化',
    start_date DATE                 COMMENT '开工日期',
    finish_date DATE                COMMENT '完工日期',
    team       VARCHAR(20)          COMMENT '班组',
    status     VARCHAR(20)          COMMENT 'PENDING 待排产 / PROCESSING 加工中 / DONE 已完工 / SETTLED 已结算',
    remark     VARCHAR(200)         COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_block (block_id),
    KEY idx_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='加工工单';

CREATE TABLE mill_layout_item (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    block_id   BIGINT                COMMENT '荒料',
    slab_id    BIGINT                COMMENT '对应大板',
    x_pos      INT                   COMMENT 'x 坐标（cm，沿荒料长度方向）',
    y_pos      INT                   COMMENT 'y 坐标（cm，沿荒料宽度方向）',
    item_width INT                   COMMENT '宽（cm）',
    item_height INT                  COMMENT '高（cm）',
    seq_no     INT                   COMMENT '锯切顺序号',
    waste_rate DECIMAL(6,2)          COMMENT '该荒料排布损耗率（%）',
    PRIMARY KEY (id),
    KEY idx_layout_block (block_id),
    KEY idx_layout_slab (slab_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='排板方案';

CREATE TABLE mill_delivery (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    delivery_no  VARCHAR(30)   NOT NULL COMMENT '发货单号',
    customer     VARCHAR(80)            COMMENT '客户',
    contact      VARCHAR(30)            COMMENT '联系人',
    phone        VARCHAR(20)            COMMENT '联系电话',
    plate_no     VARCHAR(20)            COMMENT '车牌',
    driver       VARCHAR(30)            COMMENT '司机',
    delivery_date DATE                  COMMENT '发货日期',
    total_amount DECIMAL(12,2)          COMMENT '总金额（元）',
    status       VARCHAR(20)            COMMENT 'LOADING 待装车 / SHIPPED 已发运 / SIGNED 已签收 / PAID 已结款',
    remark       VARCHAR(200)           COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_delivery_no (delivery_no),
    KEY idx_delivery_status (status),
    KEY idx_delivery_date (delivery_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库发货单';

CREATE TABLE mill_delivery_item (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    delivery_id BIGINT                COMMENT '发货单',
    slab_id    BIGINT                 COMMENT '大板',
    slab_code  VARCHAR(30)            COMMENT '大板编号快照',
    quantity   INT                    COMMENT '数量（片）',
    area_m2    DECIMAL(10,2)          COMMENT '面积（m²）',
    unit_price DECIMAL(10,2)          COMMENT '单价（元/m²）',
    amount     DECIMAL(12,2)          COMMENT '金额（元）= 数量×面积×单价',
    PRIMARY KEY (id),
    KEY idx_item_delivery (delivery_id),
    KEY idx_item_slab (slab_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='发货大板明细';

-- 模块六：大板质量隔离记录（同一大板同一时刻只允许一条未收口记录）
CREATE TABLE mill_quality_hold (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    hold_no         VARCHAR(30)  NOT NULL COMMENT '隔离记录号，如 QH-2026-0001',
    slab_id         BIGINT       NOT NULL COMMENT '大板',
    round_no        INT          NOT NULL DEFAULT 1 COMMENT '第几轮隔离（放行后再发现问题新开一轮）',
    defect_type     VARCHAR(20)  NOT NULL COMMENT '缺陷类型：CHIPPED 崩边 / CRACK 裂纹 / COLOR_DIFF 色差 / HOLE 孔洞 / SCRATCH 划伤 / OTHER 其他',
    severity        VARCHAR(10)  NOT NULL COMMENT '严重程度：MINOR 轻微 / MAJOR 较重 / CRITICAL 严重',
    affect_position VARCHAR(100)          COMMENT '影响位置（如 左上角距边 10cm）',
    found_stage     VARCHAR(20)           COMMENT '发现环节：CUTTING 锯切 / GRINDING 打磨 / POLISHING 抛光 / INSPECTION 成检 / WAREHOUSE 入库 / LOADING 装车前 / FEEDBACK 客户反馈',
    site_note       VARCHAR(500)          COMMENT '现场说明',
    dispose_advice  VARCHAR(500)          COMMENT '处置建议',
    reporter        VARCHAR(30)           COMMENT '发现 / 登记人',
    status          VARCHAR(20)  NOT NULL COMMENT 'ISOLATED 隔离中 / PENDING_RECHECK 待复检 / RELEASED 已放行 / SCRAPPED 已报废',
    -- 建立时的基础资料快照（此后基础资料变化不改写历史证据）
    snap_slab_code  VARCHAR(30)  NOT NULL COMMENT '大板编号快照',
    snap_block_code VARCHAR(30)           COMMENT '所属荒料编号快照',
    snap_block_id   BIGINT                COMMENT '所属荒料 id 快照',
    snap_grade      VARCHAR(10)           COMMENT '质量等级快照',
    snap_location   VARCHAR(30)           COMMENT '库位快照',
    snap_slab_status VARCHAR(20)          COMMENT '大板状态快照',
    -- 放行 / 报废收口信息
    closed_remark   VARCHAR(500)          COMMENT '收口意见（放行结论 / 报废依据）',
    closed_by       VARCHAR(30)           COMMENT '收口操作人',
    closed_at       DATETIME              COMMENT '收口时间',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本（并发复检 / 重复点击只有一个生效）',
    created_at      DATETIME     NOT NULL COMMENT '建立时间',
    updated_at      DATETIME     NOT NULL COMMENT '最近流转时间',
    -- 生成列：记录未收口（隔离中 / 待复检）时等于 slab_id，收口后为 NULL。
    -- 配合唯一索引，在数据库层强制「同一大板同一时刻只有一条未收口记录」。
    active_slab_id  BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('ISOLATED','PENDING_RECHECK') THEN slab_id ELSE NULL END) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hold_no (hold_no),
    UNIQUE KEY uk_hold_active_slab (active_slab_id),
    KEY idx_hold_slab (slab_id),
    KEY idx_hold_status (status),
    KEY idx_hold_defect (defect_type),
    KEY idx_hold_severity (severity),
    KEY idx_hold_block (snap_block_code),
    KEY idx_hold_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='大板质量隔离记录';

-- 模块七：质量隔离流转留痕（只追加，不更新、不删除）
CREATE TABLE mill_quality_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    hold_id     BIGINT       NOT NULL COMMENT '质量隔离记录',
    event_type  VARCHAR(25)  NOT NULL COMMENT 'CREATE 建立 / SUBMIT_RECHECK 送复检 / RECHECK 复检意见 / RELEASE 放行 / SCRAP 报废',
    from_status VARCHAR(20)           COMMENT '流转前状态',
    to_status   VARCHAR(20)           COMMENT '流转后状态',
    conclusion  VARCHAR(20)           COMMENT '复检结论：PASS 合格放行 / REWORK 返工观察 / SCRAP 报废（仅复检类事件）',
    content     VARCHAR(500)          COMMENT '现场说明 / 复检意见 / 处置意见',
    operator    VARCHAR(30)           COMMENT '操作人',
    created_at  DATETIME     NOT NULL COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_event_hold (hold_id),
    KEY idx_event_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='质量隔离流转留痕';

-- 模块八：锯切批次（开锯冻结 + 交接 + 差异待核 + 收口固化，逐片产出谱系的根）
CREATE TABLE mill_saw_batch (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    batch_no        VARCHAR(30)  NOT NULL COMMENT '锯切批次号，如 SB-2026-0001',
    order_id        BIGINT       NOT NULL COMMENT '加工工单',
    block_id        BIGINT       NOT NULL COMMENT '荒料（开锯时冻结）',
    snap_order_no   VARCHAR(30)  NOT NULL COMMENT '工单号快照',
    snap_block_code VARCHAR(30)  NOT NULL COMMENT '荒料编号快照',
    saw_type        VARCHAR(20)  NOT NULL COMMENT '锯切方式快照：FRAME/DISC/WIRE/COMBO',
    team            VARCHAR(20)           COMMENT '班组快照',
    plan_count      INT          NOT NULL COMMENT '计划板数快照（取工单计划板数）',
    status          VARCHAR(24)  NOT NULL COMMENT 'OPEN 开锯中(交接中) / PENDING_RECONCILE 差异待核 / READY 可收口(差异已核清待收口) / CLOSED 已收口',
    opened_by       VARCHAR(30)           COMMENT '开锯操作人',
    opened_at       DATETIME     NOT NULL COMMENT '开锯建批时间',
    handover_by     VARCHAR(30)           COMMENT '交接提交操作人',
    handover_at     DATETIME              COMMENT '交接提交时间（为空表示班组还在逐片登记）',
    closed_by       VARCHAR(30)           COMMENT '收口操作人',
    closed_at       DATETIME              COMMENT '收口时间',
    -- 收口固化的谱系计数（收口前为 0/NULL，收口后只读）
    finished_count  INT          NOT NULL DEFAULT 0 COMMENT '成品片数（OUTPUT_TYPE=FINISHED 的 ACTIVE 产出）',
    remnant_count   INT          NOT NULL DEFAULT 0 COMMENT '可返切余料片数（REMNANT 的 ACTIVE 产出）',
    saw_loss_count  INT          NOT NULL DEFAULT 0 COMMENT '锯耗条数（SAW_LOSS 的 ACTIVE 产出）',
    qualified_count INT          NOT NULL DEFAULT 0 COMMENT '合格实产片数（非次品成品，收口时据此推进工单完成数）',
    remark          VARCHAR(200)          COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_saw_batch_no (batch_no),
    KEY idx_saw_batch_order (order_id),
    KEY idx_saw_batch_block (block_id),
    KEY idx_saw_batch_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='锯切批次';

-- 批次排板顺序冻结快照：建立时从 mill_layout_item 复制，之后重排 / 改板不改写
CREATE TABLE mill_saw_batch_item (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    batch_id    BIGINT   NOT NULL COMMENT '锯切批次',
    seq_no      INT      NOT NULL COMMENT '锯切顺序号（冻结）',
    slab_id     BIGINT            COMMENT '排板时已对应大板（可空，仅作计划参考）',
    plan_width  INT               COMMENT '计划宽（cm，冻结，沿排板长向）',
    plan_height INT               COMMENT '计划高（cm，冻结）',
    x_pos       INT               COMMENT '排板 x 坐标快照',
    y_pos       INT               COMMENT '排板 y 坐标快照',
    PRIMARY KEY (id),
    KEY idx_sbi_batch (batch_id),
    KEY idx_sbi_seq (batch_id, seq_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='锯切批次排板顺序冻结快照';

-- 模块十：逐片产出登记（成品 / 可返切余料 / 锯耗回到同一批次核对）
CREATE TABLE mill_saw_output (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id        BIGINT       NOT NULL COMMENT '所属锯切批次（归属只能更正，不能静默覆盖）',
    slab_id         BIGINT                COMMENT '产出大板（锯耗为空；成品/余料必填且在荒料下）',
    source_slab_id  BIGINT                COMMENT '可返切余料的来源大板（返切谱系，作废时查是否被后续批次引用）',
    seq_no          INT                   COMMENT '对应排板顺序号（锯耗可空；允许多片同号，交接时按重复号待核）',
    output_type     VARCHAR(12)  NOT NULL COMMENT 'FINISHED 成品 / REMNANT 可返切余料 / SAW_LOSS 锯耗',
    actual_length   INT                   COMMENT '实际长（cm，锯耗为空）',
    actual_width    INT                   COMMENT '实际宽（cm，锯耗为空）',
    actual_thickness DECIMAL(5,1)         COMMENT '实际厚（cm）',
    grade           VARCHAR(10)           COMMENT '产出等级 A/B/C/DEFECT（锯耗为空；DEFECT 不计合格实产）',
    finish          VARCHAR(20)           COMMENT '表面处理（新建大板时落）',
    loss_note       VARCHAR(200)          COMMENT '锯耗说明（为空即「未解释锯耗」，待核）',
    status          VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 有效 / VOID 已作废（归属更正/差异作废，记录保留）',
    client_token    VARCHAR(64)  NOT NULL COMMENT '现场幂等键：断网重试/重复扫码同键只返回首次产出',
    operator        VARCHAR(30)           COMMENT '登记/最近更正操作人',
    registered_at   DATETIME     NOT NULL COMMENT '首次登记时间',
    updated_at      DATETIME     NOT NULL COMMENT '最近更正时间',
    -- 生成列：产出有效且挂大板时等于 slab_id，锯耗 / 已作废为 NULL。
    -- 配合唯一索引在数据库层强制「同一块大板同一时刻只能归属于一个批次的一条产出」，
    -- 跨批次重复扫码 / 并发登记只有一条成功，后到者读到先到归属并 409 提示，不能静默覆盖。
    active_slab_id  BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' AND slab_id IS NOT NULL THEN slab_id ELSE NULL END) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_saw_output_token (batch_id, client_token),
    UNIQUE KEY uk_saw_output_active_slab (active_slab_id),
    KEY idx_saw_output_batch (batch_id),
    KEY idx_saw_output_slab (slab_id),
    KEY idx_saw_output_source (source_slab_id),
    KEY idx_saw_output_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='锯切批次逐片产出';

-- 模块十一：计划 vs 实产差异（缺号 / 重复号 / 尺寸超差 / 数量不符 / 未解释锯耗）
CREATE TABLE mill_saw_discrepancy (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id        BIGINT       NOT NULL COMMENT '所属锯切批次',
    output_id       BIGINT                COMMENT '关联逐片产出（缺号 / 数量不符类可空）',
    diff_type       VARCHAR(24)  NOT NULL COMMENT 'MISSING_SEQ 缺号 / DUP_SEQ 重复号 / SIZE_TOLERANCE 尺寸超差 / COUNT_MISMATCH 数量不符 / UNEXPLAINED_SAW_LOSS 未解释锯耗',
    seq_no          INT                   COMMENT '涉及顺序号',
    detail          VARCHAR(500) NOT NULL COMMENT '差异说明（计划值/实值，保留核对依据）',
    status          VARCHAR(12)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN 差异待核 / CONFIRMED 已确认实际结果 / CORRECTED 已更正归属',
    reason          VARCHAR(300)          COMMENT '核清原因（确认/更正必填，不得静默丢弃现场记录）',
    resolved_by     VARCHAR(30)           COMMENT '核清操作人',
    resolved_at     DATETIME              COMMENT '核清时间',
    created_at      DATETIME     NOT NULL COMMENT '差异生成时间',
    PRIMARY KEY (id),
    KEY idx_saw_diff_batch (batch_id),
    KEY idx_saw_diff_status (status),
    KEY idx_saw_diff_type (diff_type),
    KEY idx_saw_diff_output (output_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='锯切计划实产差异待核';

-- 模块十二：批次与产出谱系留痕（只追加，不更新不删除；历次更正的变更前后都在这里）
CREATE TABLE mill_saw_output_audit (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id        BIGINT       NOT NULL COMMENT '事件发生的锯切批次',
    output_id       BIGINT                COMMENT '涉及逐片产出（建批/收口类事件可空）',
    event_type      VARCHAR(24)  NOT NULL COMMENT 'CREATE_BATCH 建批 / REGISTER_OUTPUT 逐片登记 / SUBMIT_HANDOVER 交接 / CONFIRM_DIFF 确认差异 / CORRECT_OUTPUT 归属更正 / VOID_OUTPUT 作废产出 / CLOSE_BATCH 收口 / RECOMPUTE 重算',
    from_content    VARCHAR(1000)         COMMENT '变更前内容（JSON/文本，建立类为空）',
    to_content      VARCHAR(1000)         COMMENT '变更后内容（JSON/文本）',
    reason          VARCHAR(300)          COMMENT '原因（差异确认/归属更正/作废必填）',
    operator        VARCHAR(30)           COMMENT '操作人',
    created_at      DATETIME     NOT NULL COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_saw_audit_batch (batch_id),
    KEY idx_saw_audit_output (output_id),
    KEY idx_saw_audit_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='锯切批次产出谱系留痕';

-- 业务单据号原子序列（按 seq_key 行锁取号，杜绝并发同号）
CREATE TABLE mill_seq (
    seq_key   VARCHAR(40)  NOT NULL COMMENT '序列键，如 QH-2026',
    seq_value BIGINT       NOT NULL DEFAULT 0 COMMENT '当前已分配序号',
    PRIMARY KEY (seq_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单据号序列';

-- ---------------------------- 荒料 ----------------------------
INSERT INTO mill_block (id, block_code, quarry, stone_type, length_cm, width_cm, height_cm,
                        volume_m3, weight_ton, inbound_date, purchase_price, supplier, status, remark) VALUES
    (1, 'BLK-2026-001', '福建南安水头矿口', 'MARBLE', 360, 220, 170, 13.46, 36.34, '2026-06-02', 3800, '南安鑫磊矿业有限公司', 'DONE', '整料无裂，主切面已取大板'),
    (2, 'BLK-2026-002', '福建南安水头矿口', 'MARBLE', 340, 210, 165, 11.78, 31.81, '2026-06-08', 3650, '南安鑫磊矿业有限公司', 'PROCESSING', '侧面有轻微色线，需避让'),
    (3, 'BLK-2026-003', '山东五莲街头矿区', 'GRANITE', 320, 200, 160, 10.24, 28.67, '2026-06-15', 2900, '五莲石材集团股份有限公司', 'DONE', '五莲灰，颗粒均匀'),
    (4, 'BLK-2026-004', '山东五莲街头矿区', 'GRANITE', 330, 205, 170, 11.50, 32.20, '2026-06-21', 3050, '五莲石材集团股份有限公司', 'PROCESSING', '含一条暗裂纹，绳锯避让'),
    (5, 'BLK-2026-005', '河南南阳独山玉矿', 'JADE', 210, 140, 95, 2.79, 8.09, '2026-07-01', 12800, '南阳独山玉矿业贸易有限公司', 'ARCHIVED', '独山玉，俏色料'),
    (6, 'BLK-2026-006', '河南南阳独山玉矿', 'JADE', 200, 135, 90, 2.43, 7.05, '2026-07-05', 13600, '南阳独山玉矿业贸易有限公司', 'PROCESSING', '独山玉，色带偏灰'),
    (7, 'BLK-2026-007', '广东云浮思劳矿区', 'QUARTZ', 300, 170, 150, 7.65, 19.89, '2026-07-12', 2200, '云浮科迪石英石有限公司', 'DONE', '石英石大料，锯路稳定'),
    (8, 'BLK-2026-008', '广东云浮思劳矿区', 'QUARTZ', 290, 160, 145, 6.73, 17.50, '2026-07-18', 2350, '云浮科迪石英石有限公司', 'PROCESSING', '边角有缺角，先修边'),
    (9, 'BLK-2026-009', '意大利卡拉拉（进口）', 'TRAVERTINE', 270, 160, 140, 6.05, 15.13, '2026-07-25', 6800, '厦门海石进出口有限公司', 'PENDING', '进口洞石，孔洞偏大'),
    (10, 'BLK-2026-010', '意大利卡拉拉（进口）', 'TRAVERTINE', 265, 155, 135, 5.55, 13.88, '2026-08-01', 7100, '厦门海石进出口有限公司', 'PENDING', '进口洞石，需补胶'),
    (11, 'BLK-2026-011', '福建南安水头矿口', 'MARBLE', 370, 225, 175, 14.57, 39.34, '2026-08-08', 3980, '南安鑫磊矿业有限公司', 'PENDING', '超大料，待框架锯排期'),
    (12, 'BLK-2026-012', '山东五莲街头矿区', 'GRANITE', 310, 195, 155, 9.37, 26.24, '2026-08-15', 3120, '五莲石材集团股份有限公司', 'PENDING', '五莲红，色差控制严格');

-- ---------------------------- 大板 ----------------------------
INSERT INTO mill_slab (id, slab_code, block_id, length_cm, width_cm, thickness_cm, area_m2,
                       finish, grade, location, status, remark) VALUES
    (1, 'SLB-0001', 1, 175, 105, 2.0, 1.84, 'POLISHED', 'A', 'A-01-01', 'OUTBOUND', ''),
    (2, 'SLB-0002', 1, 170, 105, 2.0, 1.79, 'HONED', 'A', 'A-01-02', 'OUTBOUND', ''),
    (3, 'SLB-0003', 1, 115, 105, 2.0, 1.21, 'POLISHED', 'B', 'A-01-03', 'RESERVED', ''),
    (4, 'SLB-0004', 1, 118, 100, 2.0, 1.18, 'POLISHED', 'A', 'A-01-04', 'RESERVED', ''),
    (5, 'SLB-0005', 1, 112, 100, 1.8, 1.12, 'HONED', 'C', 'A-01-05', 'INSTOCK', ''),
    (6, 'SLB-0006', 2, 165, 100, 2.0, 1.65, 'POLISHED', 'A', 'A-02-01', 'OUTBOUND', ''),
    (7, 'SLB-0007', 2, 160, 100, 2.0, 1.60, 'HONED', 'B', 'A-02-02', 'OUTBOUND', ''),
    (8, 'SLB-0008', 2, 110, 100, 2.0, 1.10, 'POLISHED', 'C', 'A-02-03', 'INSTOCK', ''),
    (9, 'SLB-0009', 2, 105, 95, 1.8, 1.00, 'HONED', 'A', 'A-02-04', 'INSTOCK', ''),
    (10, 'SLB-0010', 2, 100, 95, 2.0, 0.95, 'POLISHED', 'DEFECT', 'A-02-05', 'SCRAP', '边部崩边超 3cm，判次品'),
    (11, 'SLB-0011', 3, 155, 95, 3.0, 1.47, 'FLAMED', 'A', 'B-01-01', 'OUTBOUND', ''),
    (12, 'SLB-0012', 3, 150, 95, 3.0, 1.43, 'FLAMED', 'B', 'B-01-02', 'OUTBOUND', ''),
    (13, 'SLB-0013', 3, 150, 90, 3.0, 1.35, 'LITCHI', 'A', 'B-01-03', 'RESERVED', ''),
    (14, 'SLB-0014', 3, 140, 90, 3.0, 1.26, 'LEATHER', 'C', 'B-01-04', 'RESERVED', ''),
    (15, 'SLB-0015', 4, 160, 100, 3.0, 1.60, 'FLAMED', 'A', 'B-02-01', 'OUTBOUND', ''),
    (16, 'SLB-0016', 4, 155, 100, 3.0, 1.55, 'POLISHED', 'A', 'B-02-02', 'OUTBOUND', ''),
    (17, 'SLB-0017', 4, 150, 95, 3.0, 1.43, 'LEATHER', 'B', 'B-02-03', 'INSTOCK', ''),
    (18, 'SLB-0018', 4, 145, 95, 3.0, 1.38, 'LITCHI', 'B', 'B-02-04', 'INSTOCK', ''),
    (19, 'SLB-0019', 5, 100, 70, 1.8, 0.70, 'POLISHED', 'A', 'C-01-01', 'OUTBOUND', ''),
    (20, 'SLB-0020', 5, 95, 70, 1.8, 0.67, 'POLISHED', 'A', 'C-01-02', 'SCRAP', '客户反馈贯穿裂，质量判废（发货单 DEL-2026-0608 仍已签收不倒改）'),
    (21, 'SLB-0021', 5, 90, 65, 1.8, 0.59, 'POLISHED', 'B', 'C-01-03', 'OUTBOUND', ''),
    (22, 'SLB-0022', 5, 85, 65, 1.8, 0.55, 'HONED', 'C', 'C-01-04', 'OUTBOUND', ''),
    (23, 'SLB-0023', 6, 95, 65, 1.8, 0.62, 'POLISHED', 'A', 'C-02-01', 'INSTOCK', ''),
    (24, 'SLB-0024', 6, 90, 60, 1.8, 0.54, 'POLISHED', 'B', 'C-02-02', 'INSTOCK', ''),
    (25, 'SLB-0025', 6, 90, 65, 1.8, 0.59, 'HONED', 'C', 'C-02-03', 'INSTOCK', ''),
    (26, 'SLB-0026', 6, 85, 60, 1.8, 0.51, 'POLISHED', 'DEFECT', 'C-02-04', 'SCRAP', '底色发黄，判次品'),
    (27, 'SLB-0027', 7, 145, 85, 2.0, 1.23, 'POLISHED', 'A', 'D-01-01', 'OUTBOUND', ''),
    (28, 'SLB-0028', 7, 140, 85, 2.0, 1.19, 'LEATHER', 'B', 'D-01-02', 'OUTBOUND', ''),
    (29, 'SLB-0029', 7, 135, 80, 2.0, 1.08, 'POLISHED', 'A', 'D-01-03', 'RESERVED', ''),
    (30, 'SLB-0030', 7, 130, 80, 2.0, 1.04, 'LEATHER', 'B', 'D-01-04', 'RESERVED', ''),
    (31, 'SLB-0031', 8, 140, 80, 2.0, 1.12, 'POLISHED', 'A', 'D-02-01', 'OUTBOUND', ''),
    (32, 'SLB-0032', 8, 135, 80, 2.0, 1.08, 'POLISHED', 'B', 'D-02-02', 'OUTBOUND', ''),
    (33, 'SLB-0033', 8, 130, 75, 2.0, 0.98, 'LEATHER', 'C', 'D-02-03', 'RESERVED', ''),
    (34, 'SLB-0034', 8, 125, 75, 2.0, 0.94, 'LEATHER', 'B', 'D-02-04', 'RESERVED', '');

-- ---------------------------- 大板（锯切批次验收：荒料 9~12 实产出的 30 片） ----------------------------
-- 块 9（已收口 SB-0001）：SLB-0101~0106，计划 90×80，全部实产吻合
INSERT INTO mill_slab (id, slab_code, block_id, length_cm, width_cm, thickness_cm, area_m2,
                       finish, grade, location, status, remark) VALUES
    (35, 'SLB-0101', 9, 90, 80, 2.0, 0.72, 'LEATHER', 'A', 'E-01-01', 'INSTOCK', 'SB-2026-0001 收口实产'),
    (36, 'SLB-0102', 9, 90, 80, 2.0, 0.72, 'LEATHER', 'A', 'E-01-02', 'INSTOCK', 'SB-2026-0001 收口实产'),
    (37, 'SLB-0103', 9, 90, 80, 2.0, 0.72, 'LEATHER', 'B', 'E-01-03', 'INSTOCK', 'SB-2026-0001 收口实产'),
    (38, 'SLB-0104', 9, 90, 80, 2.0, 0.72, 'LEATHER', 'A', 'E-01-04', 'INSTOCK', 'SB-2026-0001 收口实产'),
    (39, 'SLB-0105', 9, 90, 80, 2.0, 0.72, 'LEATHER', 'B', 'E-01-05', 'INSTOCK', 'SB-2026-0001 收口实产'),
    (40, 'SLB-0106', 9, 90, 80, 2.0, 0.72, 'LEATHER', 'A', 'E-01-06', 'INSTOCK', 'SB-2026-0001 收口实产'),
-- 块 10（SB-0002 差异待核）：SLB-0201~0205 + 1 条锯耗；SLB-0204 尺寸超差
    (41, 'SLB-0201', 10, 88, 77, 2.0, 0.68, 'HONED', 'A', 'E-02-01', 'INSTOCK', 'SB-2026-0002 交接实产'),
    (42, 'SLB-0202', 10, 88, 77, 2.0, 0.68, 'HONED', 'A', 'E-02-02', 'INSTOCK', 'SB-2026-0002 交接实产'),
    (43, 'SLB-0203', 10, 88, 77, 2.0, 0.68, 'HONED', 'B', 'E-02-03', 'INSTOCK', 'SB-2026-0002 交接实产'),
    (44, 'SLB-0204', 10, 76, 77, 2.0, 0.59, 'HONED', 'B', 'E-02-04', 'INSTOCK', 'SB-2026-0002 交接实产，长偏短 12cm（超差待核）'),
    (45, 'SLB-0205', 10, 88, 77, 2.0, 0.68, 'HONED', 'A', 'E-02-05', 'INSTOCK', 'SB-2026-0002 交接实产'),
-- 块 11（SB-0003/SB-0004 开锯中）：成品 6 片 + 余料 2 片（SB-0004 为 SB-0003 成品返切，构成「被后续批次引用」）
    (46, 'SLB-0301', 11, 92, 112, 2.0, 1.03, 'POLISHED', 'A', 'E-03-01', 'INSTOCK', 'SB-2026-0003 交接实产'),
    (47, 'SLB-0302', 11, 92, 112, 2.0, 1.03, 'POLISHED', 'A', 'E-03-02', 'INSTOCK', 'SB-2026-0003 交接实产'),
    (48, 'SLB-0303', 11, 92, 112, 2.0, 1.03, 'POLISHED', 'B', 'E-03-03', 'INSTOCK', 'SB-2026-0003 交接实产'),
    (49, 'SLB-0304', 11, 92, 112, 2.0, 1.03, 'POLISHED', 'A', 'E-03-04', 'INSTOCK', 'SB-2026-0003 交接实产'),
    (50, 'SLB-0401', 11, 44, 60, 2.0, 0.26, 'POLISHED', 'C', 'E-03-05', 'INSTOCK', 'SB-2026-0004 返切实产，来源 SLB-0301，可再次返切'),
    (51, 'SLB-0402', 11, 44, 60, 2.0, 0.26, 'POLISHED', 'C', 'E-03-06', 'INSTOCK', 'SB-2026-0004 返切实产，来源 SLB-0302，可再次返切'),
    (52, 'SLB-0403', 11, 92, 112, 2.0, 1.03, 'POLISHED', 'A', 'E-03-07', 'INSTOCK', 'SB-2026-0004 交接实产'),
    (53, 'SLB-0404', 11, 92, 112, 2.0, 1.03, 'POLISHED', 'B', 'E-03-08', 'INSTOCK', 'SB-2026-0004 交接实产'),
-- 块 12（SB-0005/SB-0006）：SB-0005 冻 1~3 序，SB-0006 冻 4~5 序；
-- SLB-0502 尺寸超差（已确认），SLB-0602 现场错扫入 SB-0006 第5序（实应归属 SB-0005 → 更正演示）。
-- 更正后 SB-0006 第4序缺号，验收脚本用新大板 SLB-0607 补登，两批才都可收口（合格 3+2=5 与计划吻合）。
    (54, 'SLB-0501', 12, 103, 97, 3.0, 1.00, 'FLAMED', 'A', 'E-04-01', 'INSTOCK', 'SB-2026-0005 交接实产'),
    (55, 'SLB-0502', 12, 96, 97, 3.0, 0.93, 'FLAMED', 'B', 'E-04-02', 'INSTOCK', 'SB-2026-0005 交接实产，长偏短 7cm（超差已确认）'),
    (56, 'SLB-0503', 12, 103, 97, 3.0, 1.00, 'FLAMED', 'A', 'E-04-03', 'INSTOCK', 'SB-2026-0005 交接实产'),
    (57, 'SLB-0601', 12, 103, 97, 3.0, 1.00, 'FLAMED', 'A', 'E-04-06', 'INSTOCK', 'SB-2026-0006 交接实产'),
    (58, 'SLB-0602', 12, 103, 97, 3.0, 1.00, 'FLAMED', 'B', 'E-04-07', 'INSTOCK', 'SB-2026-0006 交接实产（现场误登，实应归属 SB-0005）'),
    (59, 'SLB-0606', 12, 103, 97, 3.0, 1.00, 'FLAMED', 'C', 'E-04-08', 'INSTOCK', 'SB-2026-0006 交接余料（不占计划顺序）');

-- ---------------------------- 加工工单 ----------------------------
-- saw_baseline_count：订单 1~8 在批次功能上线前已手工登记完成数，基线保持 NULL（页面标「待补交接」）；
-- 订单 9~12 纳入锯切批次交接：首个批次建立时基线固化为当时完成数（验收数据中均为 0）。
INSERT INTO mill_cutting_order (id, order_no, block_id, saw_type, plan_count, done_count, saw_baseline_count,
                                start_date, finish_date, team, status, remark) VALUES
    (1, 'CO-2026-0001', 1, 'FRAME', 5, 5, NULL, '2026-06-03', '2026-06-09', '甲班', 'DONE', ''),
    (2, 'CO-2026-0002', 2, 'FRAME', 5, 3, NULL, '2026-06-10', NULL, '甲班', 'PROCESSING', '锯条已更换一次，进度偏慢'),
    (3, 'CO-2026-0003', 3, 'DISC', 4, 4, NULL, '2026-06-16', '2026-06-22', '乙班', 'DONE', ''),
    (4, 'CO-2026-0004', 4, 'WIRE', 4, 2, NULL, '2026-06-23', NULL, '乙班', 'PROCESSING', '暗裂纹避让，实际板数待定'),
    (5, 'CO-2026-0005', 5, 'WIRE', 4, 4, NULL, '2026-07-02', '2026-07-06', '丙班', 'SETTLED', ''),
    (6, 'CO-2026-0006', 6, 'COMBO', 4, 1, NULL, '2026-07-07', NULL, '丙班', 'PROCESSING', '玉石料慢切，单片 6 小时'),
    (7, 'CO-2026-0007', 7, 'FRAME', 4, 4, NULL, '2026-07-13', '2026-07-19', '甲班', 'DONE', ''),
    (8, 'CO-2026-0008', 8, 'DISC', 4, 3, NULL, '2026-07-20', NULL, '乙班', 'PROCESSING', '圆盘锯吃刀量调小'),
    (9, 'CO-2026-0009', 9, 'COMBO', 6, 6, 0, '2026-10-01', '2026-10-01', '丙班', 'DONE', '批次 SB-2026-0001 已收口：计划吻合，按合格实产推进至 6/6'),
    (10, 'CO-2026-0010', 10, 'WIRE', 6, 0, 0, '2026-10-01', NULL, '甲班', 'PROCESSING', '批次 SB-2026-0002 差异待核，完成数暂不增加'),
    (11, 'CO-2026-0011', 11, 'FRAME', 8, 0, 0, '2026-10-01', NULL, '乙班', 'PROCESSING', '两开锯批次交接中，用于断网重试/跨批次占用验收'),
    (12, 'CO-2026-0012', 12, 'DISC', 5, 0, 0, '2026-10-01', NULL, '丙班', 'PROCESSING', '两批次差异待核/可收口，用于差异更正与并发收口验收');

-- ---------------------------- 排板方案 ----------------------------
INSERT INTO mill_layout_item (id, block_id, slab_id, x_pos, y_pos, item_width, item_height, seq_no, waste_rate) VALUES
    (1, 1, 1, 0, 0, 175, 105, 1, 9.97),
    (2, 1, 2, 175, 0, 170, 105, 2, 9.97),
    (3, 1, 3, 0, 105, 115, 105, 3, 9.97),
    (4, 1, 4, 115, 105, 118, 100, 4, 9.97),
    (5, 1, 5, 233, 105, 112, 100, 5, 9.97),
    (6, 2, 6, 0, 0, 165, 100, 1, 11.80),
    (7, 2, 7, 165, 0, 160, 100, 2, 11.80),
    (8, 2, 8, 0, 100, 110, 100, 3, 11.80),
    (9, 2, 9, 110, 100, 105, 95, 4, 11.80),
    (10, 2, 10, 215, 100, 100, 95, 5, 11.80),
    (11, 3, 11, 0, 0, 155, 95, 1, 13.95),
    (12, 3, 12, 155, 0, 150, 95, 2, 13.95),
    (13, 3, 13, 0, 95, 150, 90, 3, 13.95),
    (14, 3, 14, 150, 95, 140, 90, 4, 13.95),
    (15, 4, 15, 0, 0, 160, 100, 1, 12.01),
    (16, 4, 16, 160, 0, 155, 100, 2, 12.01),
    (17, 4, 17, 0, 100, 150, 95, 3, 12.01),
    (18, 4, 18, 150, 100, 145, 95, 4, 12.01),
    (19, 5, 19, 0, 0, 100, 70, 1, 14.88),
    (20, 5, 20, 100, 0, 95, 70, 2, 14.88),
    (21, 5, 21, 0, 70, 90, 65, 3, 14.88),
    (22, 5, 22, 90, 70, 85, 65, 4, 14.88),
    (23, 6, 23, 0, 0, 95, 65, 1, 16.57),
    (24, 6, 25, 95, 0, 90, 65, 2, 16.57),
    (25, 6, 24, 0, 65, 90, 60, 3, 16.57),
    (26, 6, 26, 90, 65, 85, 60, 4, 16.57),
    (27, 7, 27, 0, 0, 145, 85, 1, 10.93),
    (28, 7, 28, 145, 0, 140, 85, 2, 10.93),
    (29, 7, 29, 0, 85, 135, 80, 3, 10.93),
    (30, 7, 30, 135, 85, 130, 80, 4, 10.93),
    (31, 8, 31, 0, 0, 140, 80, 1, 11.37),
    (32, 8, 32, 140, 0, 135, 80, 2, 11.37),
    (33, 8, 33, 0, 80, 130, 75, 3, 11.37),
    (34, 8, 34, 130, 80, 125, 75, 4, 11.37);

-- ---------------------------- 排板方案（荒料 9~12：批次冻结的计划排板来源） ----------------------------
-- 块 9（270×160 面）：3×2 共 6 片，计划 90×80
INSERT INTO mill_layout_item (id, block_id, slab_id, x_pos, y_pos, item_width, item_height, seq_no, waste_rate) VALUES
    (35, 9, 35, 0, 0, 90, 80, 1, 0.00),
    (36, 9, 36, 90, 0, 90, 80, 2, 0.00),
    (37, 9, 37, 180, 0, 90, 80, 3, 0.00),
    (38, 9, 38, 0, 80, 90, 80, 4, 0.00),
    (39, 9, 39, 90, 80, 90, 80, 5, 0.00),
    (40, 9, 40, 180, 80, 90, 80, 6, 0.00),
-- 块 10（265×155 面）：3×2 共 6 片，计划 88×77
    (41, 10, 41, 0, 0, 88, 77, 1, 0.00),
    (42, 10, 42, 88, 0, 88, 77, 2, 0.00),
    (43, 10, 43, 176, 0, 88, 77, 3, 0.00),
    (44, 10, 44, 0, 77, 88, 77, 4, 0.00),
    (45, 10, 45, 88, 77, 88, 77, 5, 0.00),
    (46, 10, NULL, 176, 77, 88, 77, 6, 0.00),
-- 块 11（370×225 面）：8 片计划，2×4 排布；SB-0003 开 1~4 序，SB-0004 开 5~8 序（含返切余料）
    (47, 11, 46, 0, 0, 92, 112, 1, 0.00),
    (48, 11, 47, 92, 0, 92, 112, 2, 0.00),
    (49, 11, 48, 184, 0, 92, 112, 3, 0.00),
    (50, 11, 49, 276, 0, 92, 112, 4, 0.00),
    (51, 11, 52, 0, 112, 92, 112, 5, 0.00),
    (52, 11, 53, 92, 112, 92, 112, 6, 0.00),
    (53, 11, NULL, 184, 112, 92, 112, 7, 0.00),
    (54, 11, NULL, 276, 112, 92, 112, 8, 0.00),
-- 块 12（310×195 面）：3+2 共 5 片计划，103×97；SB-0005 冻 1~3 序、SB-0006 冻 4~5 序
    (55, 12, 54, 0, 0, 103, 97, 1, 0.00),
    (56, 12, 55, 103, 0, 103, 97, 2, 0.00),
    (57, 12, 56, 206, 0, 103, 97, 3, 0.00),
    (58, 12, 57, 0, 97, 103, 97, 4, 0.00),
    (59, 12, NULL, 103, 97, 103, 97, 5, 0.00);

-- ---------------------------- 出库发货单 ----------------------------
INSERT INTO mill_delivery (id, delivery_no, customer, contact, phone, plate_no, driver,
                           delivery_date, total_amount, status, remark) VALUES
    (1, 'DEL-2026-0601', '广州华诚装饰工程有限公司', '陈志明', '13802241188', '粤A·8H52L', '刘建国', '2026-07-06', 1742.40, 'PAID', '首单，含装卸费'),
    (2, 'DEL-2026-0602', '深圳金地石材有限公司', '李文彬', '13902997766', '粤B·5K19T', '王海涛', '2026-07-12', 1690.00, 'SIGNED', ''),
    (3, 'DEL-2026-0603', '东莞宏远建材有限公司', '周敏', '13712635520', '粤S·2Q73M', '陈水生', '2026-07-18', 1102.00, 'SIGNED', ''),
    (4, 'DEL-2026-0604', '厦门海沧建设集团', '吴国荣', '13606018842', '闽D·6L08K', '林志强', '2026-07-24', 1354.50, 'SHIPPED', '海运拼柜，需木箱加固'),
    (5, 'DEL-2026-0605', '佛山顺德精工装饰', '梁晓峰', '13509283371', '粤E·9P41N', '黄伟明', '2026-07-28', 1476.20, 'SHIPPED', ''),
    (6, 'DEL-2026-0606', '中山小榄石材商行', '冯少华', '13420297755', '粤T·3R62F', '张永昌', '2026-08-02', 1276.00, 'SHIPPED', ''),
    (7, 'DEL-2026-0607', '郑州玉雕工艺厂', '赵玉山', '13703812299', '豫A·7D25J', '孙铁柱', '2026-08-06', 4000.00, 'SIGNED', '玉石件，单独木架包装'),
    (8, 'DEL-2026-0608', '南阳宝玉文化传媒', '王雪梅', '13903776618', '豫R·1F84B', '马长顺', '2026-08-09', 4032.00, 'SIGNED', ''),
    (9, 'DEL-2026-0609', '珠海横琴置业工程部', '郑家豪', '13509812233', '粤C·4M19P', '何俊杰', '2026-08-13', 1147.20, 'LOADING', '待装车，客户自提'),
    (10, 'DEL-2026-0610', '惠州大亚湾装饰工程', '罗建华', '13602745581', '粤L·8V37D', '曾德胜', '2026-08-16', 991.80, 'LOADING', ''),
    (11, 'DEL-2026-0611', '汕头龙湖石材市场', '许泽凯', '13802339907', '粤D·5W62H', '姚楚明', '2026-08-19', 1293.20, 'LOADING', ''),
    (12, 'DEL-2026-0612', '江门蓬江建材中心', '甄惠玲', '13702287764', '粤J·2Y95G', '梁根发', '2026-08-22', 1113.60, 'LOADING', '尾单，装车前再复核');

-- ---------------------------- 发货大板明细 ----------------------------
INSERT INTO mill_delivery_item (id, delivery_id, slab_id, slab_code, quantity, area_m2, unit_price, amount) VALUES
    (1, 1, 1, 'SLB-0001', 1, 1.84, 480, 883.20),
    (2, 1, 2, 'SLB-0002', 1, 1.79, 480, 859.20),
    (3, 2, 6, 'SLB-0006', 1, 1.65, 520, 858.00),
    (4, 2, 7, 'SLB-0007', 1, 1.60, 520, 832.00),
    (5, 3, 11, 'SLB-0011', 1, 1.47, 380, 558.60),
    (6, 3, 12, 'SLB-0012', 1, 1.43, 380, 543.40),
    (7, 4, 15, 'SLB-0015', 1, 1.60, 430, 688.00),
    (8, 4, 16, 'SLB-0016', 1, 1.55, 430, 666.50),
    (9, 5, 27, 'SLB-0027', 1, 1.23, 610, 750.30),
    (10, 5, 28, 'SLB-0028', 1, 1.19, 610, 725.90),
    (11, 6, 31, 'SLB-0031', 1, 1.12, 580, 649.60),
    (12, 6, 32, 'SLB-0032', 1, 1.08, 580, 626.40),
    (13, 7, 19, 'SLB-0019', 1, 0.70, 3200, 2240.00),
    (14, 7, 22, 'SLB-0022', 1, 0.55, 3200, 1760.00),
    (15, 8, 20, 'SLB-0020', 1, 0.67, 3200, 2144.00),
    (16, 8, 21, 'SLB-0021', 1, 0.59, 3200, 1888.00),
    (17, 9, 3, 'SLB-0003', 1, 1.21, 480, 580.80),
    (18, 9, 4, 'SLB-0004', 1, 1.18, 480, 566.40),
    (19, 10, 13, 'SLB-0013', 1, 1.35, 380, 513.00),
    (20, 10, 14, 'SLB-0014', 1, 1.26, 380, 478.80),
    (21, 11, 29, 'SLB-0029', 1, 1.08, 610, 658.80),
    (22, 11, 30, 'SLB-0030', 1, 1.04, 610, 634.40),
    (23, 12, 33, 'SLB-0033', 1, 0.98, 580, 568.40),
    (24, 12, 34, 'SLB-0034', 1, 0.94, 580, 545.20);

-- ---------------------------- 质量隔离记录（含历史轮次与留痕） ----------------------------
-- 1) SLB-0005 隔离中（在库，未挂发货单）
INSERT INTO mill_quality_hold (id, hold_no, slab_id, round_no, defect_type, severity, affect_position,
                               found_stage, site_note, dispose_advice, reporter, status,
                               snap_slab_code, snap_block_code, snap_block_id, snap_grade, snap_location,
                               snap_slab_status, closed_remark, closed_by, closed_at, version, created_at, updated_at) VALUES
    (1, 'QH-2026-0001', 5, 1, 'CHIPPED', 'MINOR', '右上角，崩口约 1.5cm',
     'POLISHING', '抛光下板时发现右上角小崩口，疑似吸盘偏压', '先隔离，复检确认能否修磨后按 B 级出', '质检-周工', 'ISOLATED',
     'SLB-0005', 'BLK-2026-001', 1, 'C', 'A-01-05', 'INSTOCK', NULL, NULL, NULL, 0, '2026-09-26 09:20:00', '2026-09-26 09:20:00'),
-- 2) SLB-0029 待复检（已预定，挂在待装车发货单 11，整单质量挂起禁运）
    (2, 'QH-2026-0002', 29, 1, 'CRACK', 'MAJOR', '板面中部横向细裂纹约 18cm',
     'LOADING', '装车前对光复检发现中部暗裂，客户订单对板面要求高', '暂停发运，复检判定放行或报废；若报废需从 DEL-2026-0611 移除', '仓库-林班长', 'PENDING_RECHECK',
     'SLB-0029', 'BLK-2026-007', 7, 'A', 'D-01-03', 'RESERVED', NULL, NULL, NULL, 1, '2026-09-27 14:05:00', '2026-09-28 08:40:00'),
-- 3) SLB-0033 待复检（已预定，挂在待装车发货单 12）
    (3, 'QH-2026-0003', 33, 1, 'COLOR_DIFF', 'MINOR', '左半边色带偏深，与同批板色差 ΔE 偏大',
     'WAREHOUSE', '客户指定同批无色差，库位复核发现与 SLB-0034 放在一起观感不一致', '送质检复检，必要时换板', '仓库-林班长', 'PENDING_RECHECK',
     'SLB-0033', 'BLK-2026-008', 8, 'C', 'D-02-03', 'RESERVED', NULL, NULL, NULL, 1, '2026-09-28 10:30:00', '2026-09-29 16:10:00'),
-- 4) SLB-0011 已放行（发运后补录，历史发货状态不倒改；演示前一轮保留）
    (4, 'QH-2026-0004', 11, 1, 'SCRATCH', 'MINOR', '表面浅划伤 2 条，约 3~5cm',
     'INSPECTION', '成检发现浅划伤，抛光可去除', '返抛后复检', '质检-周工', 'RELEASED',
     'SLB-0011', 'BLK-2026-003', 3, 'A', 'B-01-01', 'OUTBOUND', '返抛后划伤消失，板面合格，同意放行', '质检-高主管', '2026-07-10 11:00:00', 1, '2026-07-08 15:00:00', '2026-07-10 11:00:00'),
-- 5) SLB-0020 已报废（已发运签收后补录，仅质量收口，不倒改发货与大板出库历史）
    (5, 'QH-2026-0005', 20, 1, 'CRACK', 'CRITICAL', '对角线贯穿裂，已贴红色禁用标识',
     'FEEDBACK', '客户现场开箱反馈沿对角贯穿裂，照片回传，玉石件不可修复', '已发运签收，只做质量报废收口；理赔另行处理，不倒改发货历史', '质检-高主管', 'SCRAPPED',
     'SLB-0020', 'BLK-2026-005', 5, 'A', 'C-01-02', 'OUTBOUND', '贯穿性裂纹不可修复，判定报废，禁止再次流转', '质检-高主管', '2026-08-15 13:30:00', 1, '2026-08-15 10:00:00', '2026-08-15 13:30:00');

INSERT INTO mill_quality_event (id, hold_id, event_type, from_status, to_status, conclusion, content, operator, created_at) VALUES
    (1, 1, 'CREATE', NULL, 'ISOLATED', NULL, '抛光下板时发现右上角小崩口，疑似吸盘偏压。处置建议：先隔离，复检确认能否修磨后按 B 级出。', '质检-周工', '2026-09-26 09:20:00'),
    (2, 2, 'CREATE', NULL, 'ISOLATED', NULL, '装车前对光复检发现板面中部横向细裂纹约 18cm，暂停装车。', '仓库-林班长', '2026-09-27 14:05:00'),
    (3, 2, 'SUBMIT_RECHECK', 'ISOLATED', 'PENDING_RECHECK', NULL, '裂纹走向平直，需质检对光并做渗透复检，判定能否放行。', '仓库-林班长', '2026-09-28 08:40:00'),
    (4, 3, 'CREATE', NULL, 'ISOLATED', NULL, '库位复核发现 SLB-0033 左半边色带偏深，与同批 SLB-0034 观感不一致。', '仓库-林班长', '2026-09-28 10:30:00'),
    (5, 3, 'SUBMIT_RECHECK', 'ISOLATED', 'PENDING_RECHECK', NULL, '客户指定同批无色差，送质检按 ΔE 标准复检。', '仓库-林班长', '2026-09-29 16:10:00'),
    (6, 4, 'CREATE', NULL, 'ISOLATED', NULL, '成检发现表面浅划伤 2 条约 3~5cm，抛光可去除。', '质检-周工', '2026-07-08 15:00:00'),
    (7, 4, 'SUBMIT_RECHECK', 'ISOLATED', 'PENDING_RECHECK', NULL, '安排返抛，完成后复检。', '质检-周工', '2026-07-09 09:00:00'),
    (8, 4, 'RECHECK', 'PENDING_RECHECK', 'PENDING_RECHECK', 'REWORK', '返抛一遍后仍有轻微痕迹，再抛一遍。', '质检-高主管', '2026-07-10 09:30:00'),
    (9, 4, 'RELEASE', 'PENDING_RECHECK', 'RELEASED', 'PASS', '返抛后划伤消失，板面合格，同意放行。', '质检-高主管', '2026-07-10 11:00:00'),
    (10, 5, 'CREATE', NULL, 'ISOLATED', NULL, '客户现场开箱反馈沿对角贯穿裂，照片回传。', '质检-高主管', '2026-08-15 10:00:00'),
    (11, 5, 'SUBMIT_RECHECK', 'ISOLATED', 'PENDING_RECHECK', NULL, '玉石件，转资深质检评定可修复性。', '质检-高主管', '2026-08-15 10:20:00'),
    (12, 5, 'SCRAP', 'PENDING_RECHECK', 'SCRAPPED', 'SCRAP', '贯穿性裂纹不可修复，判定报废，禁止再次流转；已发运签收，发货历史不倒改。', '质检-高主管', '2026-08-15 13:30:00');

-- 隔离记录号序列：种子已占用 QH-2026-0001~0005
INSERT INTO mill_seq (seq_key, seq_value) VALUES ('QH-2026', 5);

-- ============================================================================
-- 模块八~十二：锯切批次交接与产出谱系 · 验收种子数据
-- ----------------------------------------------------------------------------
-- 验收场景分布：
--   SB-2026-0001（订单9）  已收口 · 计划吻合：6/6 合格实产，收口后工单完成数=基线0+6
--   SB-2026-0002（订单10） 差异待核：尺寸超差 + 未解释锯耗，完成数保持 0 不提前增加
--   SB-2026-0003（订单11） 开锯中：4 成品，用于断网重试/重复扫码幂等验收
--   SB-2026-0004（订单11） 开锯中：2 成品 + 2 返切余料（来源 SB-0003 成品，被后续批次引用）
--   SB-2026-0005（订单12） 可收口：尺寸超差已确认、缺号待跨批次更正后收口（并发收口验收）
--   SB-2026-0006（订单12） 差异待核：SLB-0602 现场误登本批（实应归属 SB-0005 第5序）
-- ============================================================================

-- ---------------------------- 锯切批次 ----------------------------
INSERT INTO mill_saw_batch (id, batch_no, order_id, block_id, snap_order_no, snap_block_code, saw_type,
                            team, plan_count, status, opened_by, opened_at, handover_by, handover_at,
                            closed_by, closed_at, finished_count, remnant_count, saw_loss_count,
                            qualified_count, remark) VALUES
    (1, 'SB-2026-0001', 9, 9, 'CO-2026-0009', 'BLK-2026-009', 'COMBO', '丙班', 6, 'CLOSED',
     '丙班-赵锯工', '2026-10-01 08:00:00', '丙班-钱交接', '2026-10-01 11:30:00',
     '车间-孙主管', '2026-10-01 11:45:00', 6, 0, 0, 6, '计划吻合，无差异，一次收口'),
    (2, 'SB-2026-0002', 10, 10, 'CO-2026-0010', 'BLK-2026-010', 'WIRE', '甲班', 6, 'PENDING_RECONCILE',
     '甲班-李锯工', '2026-10-01 08:10:00', '甲班-周交接', '2026-10-01 12:05:00',
     NULL, NULL, 0, 0, 0, 0, '第4序尺寸超差 + 第6序锯耗未说明，差异未核清不能收口'),
    (3, 'SB-2026-0003', 11, 11, 'CO-2026-0011', 'BLK-2026-011', 'FRAME', '乙班', 4, 'OPEN',
     '乙班-吴锯工', '2026-10-01 07:50:00', NULL, NULL,
     NULL, NULL, 0, 0, 0, 0, '开锯中，本批冻 1~4 序，前 4 序成品已逐片登记（含一次断网重试的幂等键）'),
    (4, 'SB-2026-0004', 11, 11, 'CO-2026-0011', 'BLK-2026-011', 'FRAME', '乙班', 4, 'OPEN',
     '乙班-郑锯工', '2026-10-01 13:20:00', NULL, NULL,
     NULL, NULL, 0, 0, 0, 0, '同块荒料第二开锯批次，本批冻 5~8 序：5/6 序成品 + 1/2 序余料返切'),
    (5, 'SB-2026-0005', 12, 12, 'CO-2026-0012', 'BLK-2026-012', 'DISC', '丙班', 3, 'PENDING_RECONCILE',
     '丙班-王锯工', '2026-10-01 08:05:00', '丙班-冯交接', '2026-10-01 11:50:00',
     NULL, NULL, 0, 0, 0, 0, '本批冻 1~3 序（工单计划 5 片分两批）：第2序尺寸超差已确认；第3序实物被 SB-0006 误扫（SLB-0602），更正后即可收口'),
    (6, 'SB-2026-0006', 12, 12, 'CO-2026-0012', 'BLK-2026-012', 'DISC', '丙班', 2, 'PENDING_RECONCILE',
     '丙班-陈锯工', '2026-10-01 13:10:00', '丙班-褚交接', '2026-10-01 16:30:00',
     NULL, NULL, 0, 0, 0, 0, '本批冻 4~5 序：第5序 SLB-0602 系误扫（实应归 SB-0005 第3序），更正后本批第5序需补登记');

-- ---------------------------- 批次排板顺序冻结快照 ----------------------------
INSERT INTO mill_saw_batch_item (id, batch_id, seq_no, slab_id, plan_width, plan_height, x_pos, y_pos) VALUES
    -- SB-0001：块 9 全 6 序
    (1, 1, 1, 35, 90, 80, 0, 0),
    (2, 1, 2, 36, 90, 80, 90, 0),
    (3, 1, 3, 37, 90, 80, 180, 0),
    (4, 1, 4, 38, 90, 80, 0, 80),
    (5, 1, 5, 39, 90, 80, 90, 80),
    (6, 1, 6, 40, 90, 80, 180, 80),
    -- SB-0002：块 10 全 6 序
    (7, 2, 1, 41, 88, 77, 0, 0),
    (8, 2, 2, 42, 88, 77, 88, 0),
    (9, 2, 3, 43, 88, 77, 176, 0),
    (10, 2, 4, 44, 88, 77, 0, 77),
    (11, 2, 5, 45, 88, 77, 88, 77),
    (12, 2, 6, NULL, 88, 77, 176, 77),
    -- SB-0003：块 11 的 1~4 序
    (13, 3, 1, 46, 92, 112, 0, 0),
    (14, 3, 2, 47, 92, 112, 92, 0),
    (15, 3, 3, 48, 92, 112, 184, 0),
    (16, 3, 4, 49, 92, 112, 276, 0),
    -- SB-0004：块 11 的 5~8 序
    (17, 4, 5, 52, 92, 112, 0, 112),
    (18, 4, 6, 53, 92, 112, 92, 112),
    (19, 4, 7, NULL, 92, 112, 184, 112),
    (20, 4, 8, NULL, 92, 112, 276, 112),
    -- SB-0005：块 12 的 1~3 序
    (21, 5, 1, 54, 103, 97, 0, 0),
    (22, 5, 2, 55, 103, 97, 103, 0),
    (23, 5, 3, 56, 103, 97, 206, 0),
    -- SB-0006：块 12 的 4~5 序
    (24, 6, 4, 57, 103, 97, 0, 97),
    (25, 6, 5, NULL, 103, 97, 103, 97);

-- ---------------------------- 逐片产出登记 ----------------------------
-- SB-0001：6 成品，尺寸全部吻合（计划 90×80）
INSERT INTO mill_saw_output (id, batch_id, slab_id, source_slab_id, seq_no, output_type, actual_length,
                             actual_width, actual_thickness, grade, finish, loss_note, status,
                             client_token, operator, registered_at, updated_at) VALUES
    (1, 1, 35, NULL, 1, 'FINISHED', 90, 80, 2.0, 'A', 'LEATHER', NULL, 'ACTIVE', 'tok-s1-01', '丙班-钱交接', '2026-10-01 09:05:00', '2026-10-01 09:05:00'),
    (2, 1, 36, NULL, 2, 'FINISHED', 90, 80, 2.0, 'A', 'LEATHER', NULL, 'ACTIVE', 'tok-s1-02', '丙班-钱交接', '2026-10-01 09:12:00', '2026-10-01 09:12:00'),
    (3, 1, 37, NULL, 3, 'FINISHED', 90, 80, 2.0, 'B', 'LEATHER', NULL, 'ACTIVE', 'tok-s1-03', '丙班-钱交接', '2026-10-01 09:20:00', '2026-10-01 09:20:00'),
    (4, 1, 38, NULL, 4, 'FINISHED', 90, 80, 2.0, 'A', 'LEATHER', NULL, 'ACTIVE', 'tok-s1-04', '丙班-钱交接', '2026-10-01 09:28:00', '2026-10-01 09:28:00'),
    (5, 1, 39, NULL, 5, 'FINISHED', 90, 80, 2.0, 'B', 'LEATHER', NULL, 'ACTIVE', 'tok-s1-05', '丙班-钱交接', '2026-10-01 09:35:00', '2026-10-01 09:35:00'),
    (6, 1, 40, NULL, 6, 'FINISHED', 90, 80, 2.0, 'A', 'LEATHER', NULL, 'ACTIVE', 'tok-s1-06', '丙班-钱交接', '2026-10-01 09:42:00', '2026-10-01 09:42:00'),
-- SB-0002：5 成品（第4序 SLB-0204 实长 76，超差 -12cm）+ 1 条未解释锯耗（第6序）
    (7, 2, 41, NULL, 1, 'FINISHED', 88, 77, 2.0, 'A', 'HONED', NULL, 'ACTIVE', 'tok-s2-01', '甲班-周交接', '2026-10-01 09:10:00', '2026-10-01 09:10:00'),
    (8, 2, 42, NULL, 2, 'FINISHED', 88, 77, 2.0, 'A', 'HONED', NULL, 'ACTIVE', 'tok-s2-02', '甲班-周交接', '2026-10-01 09:18:00', '2026-10-01 09:18:00'),
    (9, 2, 43, NULL, 3, 'FINISHED', 88, 77, 2.0, 'B', 'HONED', NULL, 'ACTIVE', 'tok-s2-03', '甲班-周交接', '2026-10-01 09:26:00', '2026-10-01 09:26:00'),
    (10, 2, 44, NULL, 4, 'FINISHED', 76, 77, 2.0, 'B', 'HONED', NULL, 'ACTIVE', 'tok-s2-04', '甲班-周交接', '2026-10-01 09:33:00', '2026-10-01 09:33:00'),
    (11, 2, 45, NULL, 5, 'FINISHED', 88, 77, 2.0, 'A', 'HONED', NULL, 'ACTIVE', 'tok-s2-05', '甲班-周交接', '2026-10-01 09:41:00', '2026-10-01 09:41:00'),
    (12, 2, NULL, NULL, 6, 'SAW_LOSS', NULL, NULL, NULL, NULL, NULL, NULL, 'ACTIVE', 'tok-s2-06', '甲班-周交接', '2026-10-01 09:50:00', '2026-10-01 09:50:00'),
-- SB-0003：4 成品（开锯中；output 14 的 token 供断网重试验收——同键重复提交只返回本行）
    (13, 3, 46, NULL, 1, 'FINISHED', 92, 112, 2.0, 'A', 'POLISHED', NULL, 'ACTIVE', 'tok-s3-01', '乙班-吴锯工', '2026-10-01 08:30:00', '2026-10-01 08:30:00'),
    (14, 3, 47, NULL, 2, 'FINISHED', 92, 112, 2.0, 'A', 'POLISHED', NULL, 'ACTIVE', 'tok-s3-02', '乙班-吴锯工', '2026-10-01 08:42:00', '2026-10-01 08:42:00'),
    (15, 3, 48, NULL, 3, 'FINISHED', 92, 112, 2.0, 'B', 'POLISHED', NULL, 'ACTIVE', 'tok-s3-03', '乙班-吴锯工', '2026-10-01 08:55:00', '2026-10-01 08:55:00'),
    (16, 3, 49, NULL, 4, 'FINISHED', 92, 112, 2.0, 'A', 'POLISHED', NULL, 'ACTIVE', 'tok-s3-04', '乙班-吴锯工', '2026-10-01 09:10:00', '2026-10-01 09:10:00'),
-- SB-0004：2 成品（5/6 序）+ 2 余料（返切自 SLB-0301/0302，序不占计划号）
    (17, 4, 50, 46, NULL, 'REMNANT', 44, 60, 2.0, 'C', 'POLISHED', NULL, 'ACTIVE', 'tok-s4-01', '乙班-郑锯工', '2026-10-01 13:40:00', '2026-10-01 13:40:00'),
    (18, 4, 51, 47, NULL, 'REMNANT', 44, 60, 2.0, 'C', 'POLISHED', NULL, 'ACTIVE', 'tok-s4-02', '乙班-郑锯工', '2026-10-01 13:52:00', '2026-10-01 13:52:00'),
    (19, 4, 52, NULL, 5, 'FINISHED', 92, 112, 2.0, 'A', 'POLISHED', NULL, 'ACTIVE', 'tok-s4-03', '乙班-郑锯工', '2026-10-01 14:05:00', '2026-10-01 14:05:00'),
    (20, 4, 53, NULL, 6, 'FINISHED', 92, 112, 2.0, 'B', 'POLISHED', NULL, 'ACTIVE', 'tok-s4-04', '乙班-郑锯工', '2026-10-01 14:18:00', '2026-10-01 14:18:00'),
-- SB-0005：本批冻 1~3 序，只登记 2 成品（第2序 SLB-0502 长 96 超差 -7cm）；
-- 第3序实物缺号（实物 SLB-0602 被 SB-0006 错扫走，见 id=25）→ 差异 #4 待跨批次归属更正。
-- 库里 SLB-0503(id=56) 是该荒料已存在但未纳入本批交接的大板（独立建账，不占本批实产）。
    (21, 5, 54, NULL, 1, 'FINISHED', 103, 97, 3.0, 'A', 'FLAMED', NULL, 'ACTIVE', 'tok-s5-01', '丙班-冯交接', '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
    (22, 5, 55, NULL, 2, 'FINISHED', 96, 97, 3.0, 'B', 'FLAMED', NULL, 'ACTIVE', 'tok-s5-02', '丙班-冯交接', '2026-10-01 09:12:00', '2026-10-01 09:12:00'),
-- SB-0006：本批冻 4~5 序。SLB-0601(id=57) 正确登记第4序；
-- SLB-0602(id=58) 是 SB-0005 第3序实物却被误扫到本批第5序（id=24，待归属更正）；
-- SLB-0606(id=59) 为不占顺序的余料。更正后本批第5序缺号，验收脚本补登新板 SLB-0607。
    (24, 6, 57, NULL, 4, 'FINISHED', 103, 97, 3.0, 'A', 'FLAMED', NULL, 'ACTIVE', 'tok-s6-01', '丙班-褚交接', '2026-10-01 14:00:00', '2026-10-01 14:00:00'),
    (25, 6, 58, NULL, 5, 'FINISHED', 103, 97, 3.0, 'B', 'FLAMED', NULL, 'ACTIVE', 'tok-s6-02', '丙班-褚交接', '2026-10-01 14:10:00', '2026-10-01 14:10:00'),
    (26, 6, 59, NULL, NULL, 'REMNANT', 103, 97, 3.0, 'C', 'FLAMED', NULL, 'ACTIVE', 'tok-s6-03', '丙班-褚交接', '2026-10-01 14:55:00', '2026-10-01 14:55:00');

-- ---------------------------- 计划实产差异（交接时生成） ----------------------------
INSERT INTO mill_saw_discrepancy (id, batch_id, output_id, diff_type, seq_no, detail, status, reason,
                                 resolved_by, resolved_at, created_at) VALUES
    -- SB-0002：尺寸超差（OPEN）+ 未解释锯耗（OPEN）
    (1, 2, 10, 'SIZE_TOLERANCE', 4,
     '第4序实际长 76cm / 宽 77cm，计划 88×77cm：长偏差 -12cm，超过 ±3cm 允差', 'OPEN', NULL, NULL, NULL, '2026-10-01 12:05:00'),
    (2, 2, 12, 'UNEXPLAINED_SAW_LOSS', 6,
     '第6序登记为锯耗但未填写锯耗说明，无法判定属正常锯路损耗还是漏片', 'OPEN', NULL, NULL, NULL, '2026-10-01 12:05:00'),
    -- SB-0005：第2序尺寸超差（已确认实际结果）+ 第3序缺号（OPEN，实物被 SB-0006 误扫，等待跨批次更正）
    (3, 5, 22, 'SIZE_TOLERANCE', 2,
     '第2序实际长 96cm / 宽 97cm，计划 103×97cm：长偏差 -7cm，超过 ±3cm 允差',
     'CONFIRMED', '该序靠近荒料端面暗裂带，班组按现场划线避让下刀，短 7cm 不影响客户排版，质检高主管确认按实际尺寸验收合格',
     '质检-高主管', '2026-10-01 14:20:00', '2026-10-01 11:50:00'),
    (4, 5, 25, 'MISSING_SEQ', 3,
     '第3序无有效产出登记（计划 103×97cm）；实物 SLB-0602 疑似被第二班次误扫入 SB-2026-0006 第5序，待更正归属',
     'OPEN', NULL, NULL, NULL, '2026-10-01 11:50:00');

-- ---------------------------- 谱系留痕（只追加） ----------------------------
INSERT INTO mill_saw_output_audit (id, batch_id, output_id, event_type, from_content, to_content, reason,
                                   operator, created_at) VALUES
    -- SB-0001 建批 / 交接 / 收口
    (1, 1, NULL, 'CREATE_BATCH', NULL,
     '冻结：工单 CO-2026-0009 / 荒料 BLK-2026-009 / 组合锯 COMBO / 丙班 / 计划 6 片 / 6 序排板', NULL,
     '丙班-赵锯工', '2026-10-01 08:00:00'),
    (2, 1, NULL, 'SUBMIT_HANDOVER', NULL,
     '交接 6 片成品（SLB-0101~0106），0 余料，0 锯耗；计划实产比对无差异', NULL,
     '丙班-钱交接', '2026-10-01 11:30:00'),
    (3, 1, NULL, 'CLOSE_BATCH', NULL,
     '收口固化：成品 6 / 余料 0 / 锯耗 0，合格实产 6；工单 CO-2026-0009 完成数 0+6=6，工单与荒料置已完工',
     '计划吻合，差异为零，准予收口', '车间-孙主管', '2026-10-01 11:45:00'),
    -- SB-0002 建批 / 交接（差异 OPEN，不收口）
    (4, 2, NULL, 'CREATE_BATCH', NULL,
     '冻结：工单 CO-2026-0010 / 荒料 BLK-2026-010 / 绳锯 WIRE / 甲班 / 计划 6 片 / 6 序排板', NULL,
     '甲班-李锯工', '2026-10-01 08:10:00'),
    (5, 2, NULL, 'SUBMIT_HANDOVER', NULL,
     '交接 5 片成品 + 1 条锯耗；比对生成差异 2 条：#1 第4序尺寸超差、#2 第6序未解释锯耗；批次进入差异待核', NULL,
     '甲班-周交接', '2026-10-01 12:05:00'),
    -- SB-0003 / SB-0004 建批（开锯中）
    (6, 3, NULL, 'CREATE_BATCH', NULL,
     '冻结：工单 CO-2026-0011 / 荒料 BLK-2026-011 / 框架锯 FRAME / 乙班 / 计划 8 片 / 本批 1~4 序', NULL,
     '乙班-吴锯工', '2026-10-01 07:50:00'),
    (7, 4, NULL, 'CREATE_BATCH', NULL,
     '冻结：工单 CO-2026-0011 / 荒料 BLK-2026-011 / 框架锯 FRAME / 乙班 / 计划 8 片 / 本批 5~8 序', NULL,
     '乙班-郑锯工', '2026-10-01 13:20:00'),
    -- SB-0005 建批 / 交接 / 第2序确认实际结果（保留变更前后）
    (8, 5, NULL, 'CREATE_BATCH', NULL,
     '冻结：工单 CO-2026-0012 / 荒料 BLK-2026-012 / 圆盘锯 DISC / 丙班 / 计划 5 片 / 本批 1~3 序', NULL,
     '丙班-王锯工', '2026-10-01 08:05:00'),
    (9, 5, NULL, 'SUBMIT_HANDOVER', NULL,
     '交接 2 片成品（第1/2序）；第3序实物缺号（SLB-0602 疑似被 SB-0006 误扫）；比对生成差异 2 条：#3 第2序尺寸超差、#4 第3序缺号；批次进入差异待核', NULL,
     '丙班-冯交接', '2026-10-01 11:50:00'),
    (10, 5, 22, 'CONFIRM_DIFF',
     '差异 #3 OPEN：SLB-0502 实长 96cm，计划 103cm，偏差 -7cm',
     '差异 #3 CONFIRMED：确认按实际结果，SLB-0502 仍计入合格实产（B 级）',
     '该序靠近荒料端面暗裂带，现场划线避让下刀，短 7cm 不影响客户排版，确认按实际尺寸验收',
     '质检-高主管', '2026-10-01 14:20:00'),
    -- SB-0006 建批 / 交接（本批冻 4~5 序；SLB-0602 系误扫 SB-0005 第3序，待归属更正）
    (11, 6, NULL, 'CREATE_BATCH', NULL,
     '冻结：工单 CO-2026-0012 / 荒料 BLK-2026-012 / 圆盘锯 DISC / 丙班 / 计划 5 片 / 本批 4~5 序', NULL,
     '丙班-陈锯工', '2026-10-01 13:10:00'),
    (12, 6, NULL, 'SUBMIT_HANDOVER', NULL,
     '交接 2 片成品（第4/5序）+ 1 片余料 SLB-0606；本批 4~5 序在本批冻结计划内齐全；注意第5序 SLB-0602 与 SB-0005 第3序实物对应，待归属更正', NULL,
     '丙班-褚交接', '2026-10-01 16:30:00');

-- 锯切批次号序列：种子已占用 SB-2026-0001~0006
INSERT INTO mill_seq (seq_key, seq_value) VALUES ('SB-2026', 6);

SET FOREIGN_KEY_CHECKS = 1;
