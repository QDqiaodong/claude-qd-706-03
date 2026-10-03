package com.stone.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stone.mill.common.BizException;
import com.stone.mill.common.ConflictException;
import com.stone.mill.dto.Dtos.SawBatchCreateReq;
import com.stone.mill.dto.Dtos.SawCloseReq;
import com.stone.mill.dto.Dtos.SawDiffResolveReq;
import com.stone.mill.dto.Dtos.SawHandoverReq;
import com.stone.mill.dto.Dtos.SawOutputRegisterReq;
import com.stone.mill.dto.Queries.SawBatchQuery;
import com.stone.mill.dto.StoneDict;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.dto.Views.SawAuditView;
import com.stone.mill.dto.Views.SawBatchItemView;
import com.stone.mill.dto.Views.SawBatchListItem;
import com.stone.mill.dto.Views.SawBatchView;
import com.stone.mill.dto.Views.SawDiffView;
import com.stone.mill.dto.Views.SawOutputView;
import com.stone.mill.dto.Views.SawRegisterResult;
import com.stone.mill.dto.Views.SawSlabTrace;
import com.stone.mill.entity.Block;
import com.stone.mill.entity.CuttingOrder;
import com.stone.mill.entity.LayoutItem;
import com.stone.mill.entity.SawBatch;
import com.stone.mill.entity.SawBatchItem;
import com.stone.mill.entity.SawDiscrepancy;
import com.stone.mill.entity.SawOutput;
import com.stone.mill.entity.SawOutputAudit;
import com.stone.mill.entity.Slab;
import com.stone.mill.repository.BlockRepository;
import com.stone.mill.repository.CuttingOrderRepository;
import com.stone.mill.repository.LayoutRepository;
import com.stone.mill.repository.SawAuditRepository;
import com.stone.mill.repository.SawBatchRepository;
import com.stone.mill.repository.SawDiscrepancyRepository;
import com.stone.mill.repository.SawOutputRepository;
import com.stone.mill.repository.SequenceRepository;
import com.stone.mill.repository.SlabRepository;

/**
 * 模块八~十二：锯切批次交接与产出谱系。
 *
 * <p>业务主线（每一步都在单事务内完成，状态变更 / 大板落账 / 差异生成 / 工单进度要么全成要么全回滚）：
 * <ol>
 *   <li>开锯建批 {@link #create}：冻结工单、荒料、锯切方式、班组、计划板数与当时排板顺序快照；
 *       首个批次建立时把工单当时完成数固化为基线，旧工单自此纳入批次口径。</li>
 *   <li>逐片登记 {@link #registerOutput}：成品 / 可返切余料 / 锯耗回到同一批次。
 *       client_token 幂等保证断网重试 / 重复扫码不产生两份；active_slab_id 唯一索引保证
 *       同一块大板只能归属一个批次，跨批次占用保留先到记录，后到者 409 看到当前归属。</li>
 *   <li>交接 {@link #submitHandover}：班组先交完，系统比对冻结计划与逐片实产生成差异
 *       （缺号 / 重复号 / 尺寸超差 / 数量不符 / 未解释锯耗）。有差异进入「差异待核」，
 *       现场记录一条不丢；未核清既不能收口，也不增加工单完成数。</li>
 *   <li>差异核清 {@link #resolveDiff}：负责人确认实际结果或更正归属 / 作废，必须填原因，
 *       变更前后写 audit；已被后续批次作为返切来源引用的产出不得直接作废。</li>
 *   <li>收口 {@link #close}：悲观行锁串行，两人同时收口 / 重复点击 / 中途失败只形成一个结果；
 *       一次性固化成品 / 余料 / 锯耗 / 合格实产计数，并按「基线 + 各已收口批次合格实产」回写工单完成数。</li>
 * </ol>
 */
@Service
public class SawBatchService {

    private final SawBatchRepository batchRepository;
    private final SawOutputRepository outputRepository;
    private final SawDiscrepancyRepository diffRepository;
    private final SawAuditRepository auditRepository;
    private final CuttingOrderRepository orderRepository;
    private final BlockRepository blockRepository;
    private final SlabRepository slabRepository;
    private final LayoutRepository layoutRepository;
    private final SequenceRepository sequenceRepository;
    private final SawTxBridge txBridge;

    public SawBatchService(SawBatchRepository batchRepository, SawOutputRepository outputRepository,
                           SawDiscrepancyRepository diffRepository, SawAuditRepository auditRepository,
                           CuttingOrderRepository orderRepository, BlockRepository blockRepository,
                           SlabRepository slabRepository, LayoutRepository layoutRepository,
                           SequenceRepository sequenceRepository, SawTxBridge txBridge) {
        this.batchRepository = batchRepository;
        this.outputRepository = outputRepository;
        this.diffRepository = diffRepository;
        this.auditRepository = auditRepository;
        this.orderRepository = orderRepository;
        this.blockRepository = blockRepository;
        this.slabRepository = slabRepository;
        this.layoutRepository = layoutRepository;
        this.sequenceRepository = sequenceRepository;
        this.txBridge = txBridge;
    }

    // ============================== 查询 ==============================

    public PageResult<SawBatchListItem> page(SawBatchQuery query) {
        List<SawBatch> batches = batchRepository.search(query);
        List<SawBatchListItem> rows = batches.stream().map(this::toListItem).collect(Collectors.toList());
        return new PageResult<>(rows, batchRepository.count(query), query.getPage(), query.getSize());
    }

    public SawBatchView detail(Long id) {
        SawBatch batch = batchRepository.findById(id)
                .orElseThrow(() -> new BizException("锯切批次不存在：id=" + id));
        return toView(batch);
    }

    /** 工单详情：该工单全部批次（含谱系），给加工工单详情内嵌用。 */
    public List<SawBatchListItem> listByOrder(Long orderId) {
        return batchRepository.listByOrder(orderId).stream()
                .map(this::toListItem).collect(Collectors.toList());
    }

    /**
     * 工单聚合口径：已纳入批次（基线非 null）后，完成数 = 基线 + Σ 已收口批次合格实产；
     * 开锯中 / 差异待核 / 可收口但未收口的批次一律不计入完成数。
     */
    public OrderSawStats orderStats(Long orderId) {
        CuttingOrder order = orderRepository.findById(orderId).orElse(null);
        OrderSawStats stats = new OrderSawStats();
        if (order == null) {
            return stats;
        }
        stats.tracked = order.getSawBaselineCount() != null;
        stats.baseline = order.getSawBaselineCount() == null ? 0 : order.getSawBaselineCount();
        List<SawBatch> batches = batchRepository.listByOrder(orderId);
        stats.batchCount = batches.size();
        int qualified = 0;
        for (SawBatch b : batches) {
            if ("CLOSED".equals(b.getStatus())) {
                qualified += b.getQualifiedCount() == null ? 0 : b.getQualifiedCount();
            }
            if ("PENDING_RECONCILE".equals(b.getStatus())) {
                stats.pendingBatchCount++;
            }
        }
        stats.qualifiedClosed = qualified;
        if (!batches.isEmpty()) {
            stats.openDiffCount = (int) diffRepository.countOpenByBatches(
                    batches.stream().map(SawBatch::getId).collect(Collectors.toList()));
        }
        return stats;
    }

    /** 工单列表/详情批量补锯切字段（数据量在工厂场景可控，沿用全站 listAll 口径）。 */
    public Map<Long, OrderSawStats> orderStatsMap() {
        List<SawBatch> all = batchRepository.listAll();
        Map<Long, List<Long>> idsByOrder = all.stream().collect(Collectors.groupingBy(
                SawBatch::getOrderId, Collectors.mapping(SawBatch::getId, Collectors.toList())));
        Map<Long, OrderSawStats> map = new HashMap<>();
        for (Map.Entry<Long, List<SawBatch>> e : all.stream()
                .collect(Collectors.groupingBy(SawBatch::getOrderId)).entrySet()) {
            Long orderId = e.getKey();
            CuttingOrder o = orderRepository.findById(orderId).orElse(null);
            OrderSawStats s = new OrderSawStats();
            s.tracked = o != null && o.getSawBaselineCount() != null;
            s.baseline = o == null || o.getSawBaselineCount() == null ? 0 : o.getSawBaselineCount();
            s.batchCount = e.getValue().size();
            for (SawBatch b : e.getValue()) {
                if ("CLOSED".equals(b.getStatus())) {
                    s.qualifiedClosed += b.getQualifiedCount() == null ? 0 : b.getQualifiedCount();
                }
                if ("PENDING_RECONCILE".equals(b.getStatus())) {
                    s.pendingBatchCount++;
                }
            }
            s.openDiffCount = (int) diffRepository.countOpenByBatches(idsByOrder.get(orderId));
            map.put(orderId, s);
        }
        return map;
    }

    /** 一批大板的当前有效产出行（大板列表 / 详情补「所属批次」用）。 */
    public Map<Long, SawOutput> activeOutputMap(List<Long> slabIds) {
        Map<Long, SawOutput> map = new HashMap<>();
        if (slabIds == null || slabIds.isEmpty()) {
            return map;
        }
        for (SawOutput o : outputRepository.findActiveBySlabIds(slabIds)) {
            map.put(o.getSlabId(), o);
        }
        return map;
    }

    /** 大板视图补批次号用（只读实体）。 */
    public SawBatch batchForView(Long batchId) {
        return batchId == null ? null : batchRepository.findById(batchId).orElse(null);
    }

    /** 大板反查：所属批次 + 涉及该板的历次更正时间线。 */
    public SawSlabTrace slabTrace(Long slabId) {
        Slab slab = slabRepository.findById(slabId)
                .orElseThrow(() -> new BizException("大板不存在：id=" + slabId));
        List<SawOutput> outputs = outputRepository.listBySlab(slabId);
        SawSlabTrace trace = new SawSlabTrace();
        trace.setSlabId(slabId);
        trace.setSlabCode(slab.getSlabCode());

        Map<Long, SawBatch> batchCache = new HashMap<>();
        Map<Long, Slab> slabs = slabMap();
        List<SawOutputView> outputViews = new ArrayList<>();
        SawOutput current = null;
        for (SawOutput o : outputs) {
            if ("ACTIVE".equals(o.getStatus())) {
                current = o;
            }
            SawBatch b = batchCache.computeIfAbsent(o.getBatchId(),
                    id -> batchRepository.findById(id).orElse(null));
            outputViews.add(toOutputView(o, slabs, batchCache));
        }
        trace.setOutputs(outputViews);

        if (current != null) {
            SawBatch b = batchRepository.findById(current.getBatchId()).orElse(null);
            trace.setCurrentOutputId(current.getId());
            trace.setCurrentSeqNo(current.getSeqNo());
            trace.setCurrentOutputTypeName(StoneDict.outputTypeName(current.getOutputType()));
            if (b != null) {
                trace.setCurrentBatchId(b.getId());
                trace.setCurrentBatchNo(b.getBatchNo());
                trace.setCurrentBatchStatus(b.getStatus());
                trace.setCurrentBatchStatusName(StoneDict.sawBatchStatusName(b.getStatus()));
            }
        }

        // 时间线：涉及该板产出的 audit，加上其所在批次的建批/交接/收口上下文
        Set<Long> outputIds = outputs.stream().map(SawOutput::getId).collect(Collectors.toSet());
        Set<Long> batchIds = outputs.stream().map(SawOutput::getBatchId).collect(Collectors.toSet());
        Map<Long, SawOutput> outputMap = outputs.stream()
                .collect(Collectors.toMap(SawOutput::getId, o -> o, (a, b) -> a));
        List<SawAuditView> audits = new ArrayList<>();
        for (SawOutputAudit a : auditRepository.listByBatches(new ArrayList<>(batchIds))) {
            boolean aboutSlab = a.getOutputId() != null && outputIds.contains(a.getOutputId());
            boolean context = a.getOutputId() == null && (
                    "CREATE_BATCH".equals(a.getEventType())
                            || "SUBMIT_HANDOVER".equals(a.getEventType())
                            || "CLOSE_BATCH".equals(a.getEventType()));
            if (aboutSlab || context) {
                audits.add(toAuditView(a, batchCache, outputMap));
            }
        }
        trace.setAudits(audits);
        return trace;
    }

    // ============================== 开锯建批 ==============================

    @Transactional
    public SawBatchView create(SawBatchCreateReq req) {
        CuttingOrder order = orderRepository.findById(req.getOrderId())
                .orElseThrow(() -> new BizException("加工工单不存在：id=" + req.getOrderId()));
        if (order.getBlockId() == null) {
            throw new BizException("该工单没有对应荒料，不能开锯建批");
        }
        Block block = blockRepository.findById(order.getBlockId())
                .orElseThrow(() -> new BizException("荒料不存在：id=" + order.getBlockId()));
        if ("ARCHIVED".equals(block.getStatus())) {
            throw new BizException("荒料 " + block.getBlockCode() + " 已结库，不能再开锯");
        }

        List<LayoutItem> layout = layoutRepository.listByBlock(block.getId());
        if (layout.isEmpty()) {
            throw new BizException("荒料 " + block.getBlockCode() + " 还没有排板方案，请先在排板图排板后再开锯（冻结排板顺序是交接核对的基准）");
        }
        List<Integer> seqNos = req.getSeqNos();
        if (seqNos != null && !seqNos.isEmpty()) {
            Set<Integer> wanted = new HashSet<>(seqNos);
            layout = layout.stream().filter(i -> wanted.contains(i.getSeqNo())).collect(Collectors.toList());
            if (layout.isEmpty()) {
                throw new BizException("所选排板顺序号在该荒料当前排板方案中不存在");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        long seq = sequenceRepository.nextValue("SB-" + now.getYear());
        SawBatch batch = new SawBatch();
        batch.setBatchNo("SB-" + now.getYear() + "-" + String.format("%04d", seq));
        batch.setOrderId(order.getId());
        batch.setBlockId(block.getId());
        batch.setSnapOrderNo(order.getOrderNo());
        batch.setSnapBlockCode(block.getBlockCode());
        batch.setSawType(order.getSawType());
        batch.setTeam(order.getTeam());
        // 计划板数冻结为本批覆盖的排板顺序数（指定 seqNos 时即分批的片数；不指定取整份排板，
        // 通常等于工单计划板数）。批次之间按冻结顺序互不串号。
        batch.setPlanCount(layout.size());
        batch.setStatus("OPEN");
        batch.setOpenedBy(req.getOpenedBy());
        batch.setOpenedAt(now);
        batch.setRemark(req.getRemark());
        batchRepository.save(batch);
        batchRepository.flush();

        for (LayoutItem item : layout) {
            SawBatchItem snap = new SawBatchItem();
            snap.setBatchId(batch.getId());
            snap.setSeqNo(item.getSeqNo());
            snap.setSlabId(item.getSlabId());
            snap.setPlanWidth(item.getItemWidth());
            snap.setPlanHeight(item.getItemHeight());
            snap.setXPos(item.getXPos());
            snap.setYPos(item.getYPos());
            batchRepository.saveItem(snap);
        }

        // 首个批次建立：固化基线完成数（旧工单自此纳入批次口径），并把工单/荒料推进到加工中
        boolean firstBatch = order.getSawBaselineCount() == null;
        if (firstBatch) {
            order.setSawBaselineCount(order.getDoneCount() == null ? 0 : order.getDoneCount());
        }
        if ("PENDING".equals(order.getStatus())) {
            order.setStatus("PROCESSING");
            if (order.getStartDate() == null) {
                order.setStartDate(LocalDate.now());
            }
        }
        orderRepository.save(order);
        if ("PENDING".equals(block.getStatus())) {
            block.setStatus("PROCESSING");
            blockRepository.save(block);
        }

        addAudit(batch.getId(), null, "CREATE_BATCH", null,
                "冻结：工单 " + order.getOrderNo() + " / 荒料 " + block.getBlockCode()
                        + " / 锯切方式 " + StoneDict.sawTypeName(order.getSawType())
                        + " / 班组 " + (order.getTeam() == null ? "—" : order.getTeam())
                        + " / 工单计划 " + order.getPlanCount() + " 片，本批冻结 " + layout.size()
                        + " 个排板顺序"
                        + (firstBatch ? "；同时固化基线完成数 " + order.getSawBaselineCount() : ""),
                null, req.getOpenedBy(), now);
        return detail(batch.getId());
    }

    // ============================== 逐片登记 ==============================

    @Transactional
    public SawRegisterResult registerOutput(Long batchId, SawOutputRegisterReq req) {
        SawBatch batch = batchRepository.lockById(batchId);
        if (batch == null) {
            throw new BizException("锯切批次不存在：id=" + batchId);
        }
        if ("CLOSED".equals(batch.getStatus())) {
            throw new BizException("批次 " + batch.getBatchNo() + " 已收口，谱系已固化，不能再登记产出");
        }
        if (!StoneDict.isValidOutputType(req.getOutputType())) {
            throw new BizException("产出类型取值非法：FINISHED 成品 / REMNANT 可返切余料 / SAW_LOSS 锯耗");
        }
        LocalDateTime now = LocalDateTime.now();

        // —— 幂等第一道：同批次同 client_token 直接返回首次产出（断网重试 / 重复扫码不生成两份） ——
        SawOutput dupToken = outputRepository.findByToken(batchId, req.getClientToken());
        if (dupToken != null) {
            SawRegisterResult r = new SawRegisterResult();
            r.setOutput(toOutputView(dupToken, slabMap(), batchMap()));
            r.setDuplicated(true);
            r.setMessage("该扫码记录已登记过（幂等键 " + req.getClientToken()
                    + "），已为你返回首次产出，没有重复生成");
            return r;
        }

        SawOutput output = new SawOutput();
        output.setBatchId(batchId);
        output.setSeqNo(req.getSeqNo());
        output.setOutputType(req.getOutputType());
        output.setClientToken(req.getClientToken());
        output.setOperator(req.getOperator());
        output.setRegisteredAt(now);
        output.setUpdatedAt(now);
        output.setStatus("ACTIVE");

        String sameSeqWarning = null;
        if ("SAW_LOSS".equals(req.getOutputType())) {
            output.setLossNote(req.getLossNote());
        } else {
            if (req.getLengthCm() == null || req.getLengthCm() <= 0
                    || req.getWidthCm() == null || req.getWidthCm() <= 0) {
                throw new BizException("成品 / 余料必须登记实际长、宽（cm，大于 0）；纯锯耗请登记为 SAW_LOSS");
            }
            String grade = req.getGrade() == null || req.getGrade().isBlank() ? "A" : req.getGrade();
            if (!StoneDict.isValidGrade(grade)) {
                throw new BizException("质量等级取值非法：A / B / C / DEFECT");
            }
            String finish = req.getFinish() == null || req.getFinish().isBlank() ? "HONED" : req.getFinish();
            if (!StoneDict.isValidFinish(finish)) {
                throw new BizException("表面处理取值非法");
            }
            output.setActualLength(req.getLengthCm());
            output.setActualWidth(req.getWidthCm());
            output.setActualThickness(req.getThicknessCm() == null
                    ? BigDecimal.valueOf(2.0) : req.getThicknessCm());
            output.setGrade(grade);
            output.setFinish(finish);

            Slab slab;
            if (req.getSlabId() != null) {
                slab = slabRepository.findById(req.getSlabId())
                        .orElseThrow(() -> new BizException("所选大板不存在：id=" + req.getSlabId()));
                if (slab.getBlockId() == null || !slab.getBlockId().equals(batch.getBlockId())) {
                    throw new BizException("大板 " + slab.getSlabCode() + " 不属于本批荒料 "
                            + batch.getSnapBlockCode() + "，不能登记到批次 " + batch.getBatchNo());
                }
            } else {
                if (req.getSlabCode() == null || req.getSlabCode().isBlank()) {
                    throw new BizException("新切大板必须填写大板编号；选用既有大板请改选 slabId");
                }
                if (slabRepository.findByCode(req.getSlabCode()) != null) {
                    throw new BizException("大板编号 " + req.getSlabCode() + " 已存在，请直接扫码选用该大板");
                }
                slab = new Slab();
                slab.setSlabCode(req.getSlabCode());
                slab.setBlockId(batch.getBlockId());
                slab.setLengthCm(req.getLengthCm());
                slab.setWidthCm(req.getWidthCm());
                slab.setThicknessCm(output.getActualThickness());
                slab.setFinish(finish);
                slab.setGrade(grade);
                slab.setLocation("");
                slab.setStatus("INSTOCK");
                slab.setAreaM2(BigDecimal.valueOf(req.getLengthCm())
                        .multiply(BigDecimal.valueOf(req.getWidthCm()))
                        .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP));
                slabRepository.save(slab);
                slabRepository.flush();
            }
            if ("REMNANT".equals(req.getOutputType()) && req.getSourceSlabId() != null) {
                Slab source = slabRepository.findById(req.getSourceSlabId()).orElse(null);
                if (source == null || source.getBlockId() == null
                        || !source.getBlockId().equals(batch.getBlockId())) {
                    throw new BizException("返切来源大板不存在或不属于本批荒料");
                }
                output.setSourceSlabId(req.getSourceSlabId());
            }
            // —— 应用层先给一次友好提示（真正并发兜底在 uk_saw_output_active_slab） ——
            SawOutput occupied = outputRepository.findActiveBySlab(slab.getId());
            if (occupied != null) {
                throw slabOccupiedConflict(slab, occupied);
            }
            output.setSlabId(slab.getId());

            // 同批同顺序已有实物产出：允许先登记（现场可能确实切重），交接时按「重复号」待核
            if (req.getSeqNo() != null) {
                long sameSeq = outputRepository.listActiveByBatch(batchId).stream()
                        .filter(o -> req.getSeqNo().equals(o.getSeqNo()))
                        .filter(o -> o.getSlabId() != null)
                        .count();
                if (sameSeq > 0) {
                    sameSeqWarning = "顺序号 " + req.getSeqNo() + " 在本批已有产出，已先登记保留，交接时将按「重复号」进入差异待核";
                }
            }
        }

        try {
            outputRepository.save(output);
            outputRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 当前事务已被唯一索引标记回滚，只能在新事务里读先到记录：
            // 1) 同批次同幂等键（理论上被批次行锁串行，防御性处理）；
            // 2) 两个批次并发登记同一大板：active_slab_id 唯一索引只放行一条，
            //    读出先到归属回带给后到人员（保留先到记录，不能静默覆盖）。
            String msg = String.valueOf(e.getMostSpecificCause() == null ? e.getMessage()
                    : e.getMostSpecificCause().getMessage());
            if (msg.contains("uk_saw_output_active_slab") && output.getSlabId() != null) {
                SawOutput winner = txBridge.findActiveOutputBySlab(output.getSlabId());
                if (winner != null) {
                    throw slabOccupiedConflict(txBridge.findSlabById(output.getSlabId()), winner);
                }
            }
            throw new BizException("登记失败：数据冲突，请刷新该批次后重试");
        }

        addAudit(batchId, output.getId(), "REGISTER_OUTPUT", null,
                describeOutput(output), null, req.getOperator(), now);

        // 已交接后补登缺失片：重算 OPEN 差异（现场记录允许先于核对，重算后缺号自然消除）
        if (batch.getHandoverAt() != null) {
            recomputeOpenDiffs(batch, req.getOperator(),
                    "交接后补登产出（幂等键 " + req.getClientToken() + "），重算差异");
        }

        SawRegisterResult result = new SawRegisterResult();
        result.setOutput(toOutputView(output, slabMap(), batchMap()));
        result.setDuplicated(false);
        result.setMessage(sameSeqWarning != null ? sameSeqWarning : "产出已登记到批次 " + batch.getBatchNo());
        return result;
    }

    // ============================== 交接提交 ==============================

    @Transactional
    public SawBatchView submitHandover(Long batchId, SawHandoverReq req) {
        SawBatch batch = batchRepository.lockById(batchId);
        if (batch == null) {
            throw new BizException("锯切批次不存在：id=" + batchId);
        }
        if ("CLOSED".equals(batch.getStatus())) {
            throw new BizException("批次已收口，不能再次交接");
        }
        if (batch.getHandoverAt() != null) {
            throw new BizException("批次已交接过，请在差异待核清单中处理；补登产出会自动重算差异");
        }
        if (outputRepository.listActiveByBatch(batchId).isEmpty()) {
            throw new BizException("还没有逐片产出登记，不能空交接（请先逐片扫码登记成品/余料/锯耗）");
        }
        LocalDateTime now = LocalDateTime.now();
        batch.setHandoverBy(req == null ? null : req.getHandoverBy());
        batch.setHandoverAt(now);
        batchRepository.save(batch);

        List<SawDiscrepancy> diffs = recomputeOpenDiffs(batch,
                req == null ? null : req.getHandoverBy(), null);
        addAudit(batchId, null, "SUBMIT_HANDOVER", null,
                "交接提交：成品 " + countActive(batchId, "FINISHED")
                        + " / 余料 " + countActive(batchId, "REMNANT")
                        + " / 锯耗 " + countActive(batchId, "SAW_LOSS")
                        + "；比对冻结计划生成差异 " + diffs.size() + " 条"
                        + (diffs.isEmpty() ? "，无差异可直接收口" : "，批次进入差异待核"),
                null, req == null ? null : req.getHandoverBy(), now);
        return detail(batchId);
    }

    // ============================== 差异核清 ==============================

    @Transactional
    public SawBatchView resolveDiff(Long diffId, SawDiffResolveReq req) {
        SawDiscrepancy diff = diffRepository.findById(diffId)
                .orElseThrow(() -> new BizException("差异记录不存在：id=" + diffId));
        if (!"OPEN".equals(diff.getStatus())) {
            throw new BizException("该差异已核清为「" + StoneDict.diffStatusName(diff.getStatus())
                    + "」，不能重复核清（变更前后已留痕）");
        }
        SawBatch batch = batchRepository.lockById(diff.getBatchId());
        if (batch == null) {
            throw new BizException("锯切批次不存在：id=" + diff.getBatchId());
        }
        if ("CLOSED".equals(batch.getStatus())) {
            throw new BizException("批次已收口，差异不能再变更");
        }
        LocalDateTime now = LocalDateTime.now();
        String reason = req.getReason().trim();

        switch (req.getAction()) {
            case "CONFIRM" -> doConfirm(diff, batch, reason, req.getOperator(), now);
            case "CORRECT_ASSIGN" -> doCorrectAssign(diff, batch, req, reason, now);
            case "CORRECT_VOID" -> doCorrectVoid(diff, batch, req, reason, now);
            default -> throw new BizException("核清方式取值非法：CONFIRM / CORRECT_ASSIGN / CORRECT_VOID");
        }
        return detail(batch.getId());
    }

    /** 确认实际结果：差异照实接受（缺片/超差/损耗经负责人确认），必须留原因。 */
    private void doConfirm(SawDiscrepancy diff, SawBatch batch, String reason, String operator, LocalDateTime now) {
        String from = "差异 #" + diff.getId() + " OPEN：" + diff.getDetail();
        if ("UNEXPLAINED_SAW_LOSS".equals(diff.getDiffType()) && diff.getOutputId() != null) {
            SawOutput loss = outputRepository.findById(diff.getOutputId()).orElse(null);
            if (loss != null && "SAW_LOSS".equals(loss.getOutputType())) {
                loss.setLossNote(reason);
                loss.setUpdatedAt(now);
                outputRepository.save(loss);
            }
        }
        diff.setStatus("CONFIRMED");
        diff.setReason(reason);
        diff.setResolvedBy(operator);
        diff.setResolvedAt(now);
        diffRepository.save(diff);
        addAudit(batch.getId(), diff.getOutputId(), "CONFIRM_DIFF", from,
                "差异 #" + diff.getId() + " CONFIRMED：确认按实际结果记账"
                        + ("SIZE_TOLERANCE".equals(diff.getDiffType()) ? "（该片仍按实产参与收口计数）" : ""),
                reason, operator, now);
        // 重算：已 CONFIRMED 的差异键会被抑制，不再重新冒出来
        recomputeOpenDiffs(batch, operator, "确认差异 #" + diff.getId() + " 后重算");
    }

    /** 更正归属：把误扫产出转到同一荒料的目标批次/顺序号，原批次与目标批次都重算。 */
    private void doCorrectAssign(SawDiscrepancy diff, SawBatch batch, SawDiffResolveReq req,
                                 String reason, LocalDateTime now) {
        // outputId 优先取请求；缺省取差异关联产出（跨批次误扫时，差异关联的是对方批次上的产出）
        Long outputId = req.getOutputId() != null ? req.getOutputId() : diff.getOutputId();
        SawOutput output = outputId == null ? null : outputRepository.findById(outputId).orElse(null);
        if (output == null || !"ACTIVE".equals(output.getStatus())) {
            throw new BizException("要更正归属的产出不存在或已作废，请指定一条有效产出");
        }
        // 来源批次：优先产出当前所在批次（允许差异批次与产出批次不同——实物被别的批次误扫的情形）
        Long sourceBatchId = output.getBatchId();
        SawBatch sourceBatch = sourceBatchId.equals(batch.getId()) ? batch
                : batchRepository.lockById(sourceBatchId);
        if (sourceBatch == null) {
            throw new BizException("产出当前所在批次不存在：id=" + sourceBatchId);
        }
        if (req.getTargetBatchId() == null) {
            throw new BizException("请选择要转入的目标批次（同一荒料的另一个未收口批次）");
        }
        if (req.getTargetBatchId().equals(sourceBatchId)) {
            throw new BizException("目标批次与产出当前所在批次相同，无需跨批次更正（同批内改顺序号可直接作废重登）");
        }
        SawBatch target = batchRepository.lockById(req.getTargetBatchId());
        if (target == null) {
            throw new BizException("目标批次不存在：id=" + req.getTargetBatchId());
        }
        if (!target.getBlockId().equals(sourceBatch.getBlockId())) {
            throw new BizException("只能在同一荒料的批次之间更正归属："
                    + sourceBatch.getSnapBlockCode() + " ≠ " + target.getSnapBlockCode());
        }
        if ("CLOSED".equals(target.getStatus())) {
            throw new BizException("目标批次 " + target.getBatchNo() + " 已收口固化，不能再接收产出");
        }

        String fromContent = "产出 #" + output.getId()
                + (output.getSlabId() == null ? "" : "（大板 " + slabCode(output.getSlabId()) + "）")
                + "：原归属 " + sourceBatch.getBatchNo() + " 第 " + output.getSeqNo() + " 序";
        Integer oldSeq = output.getSeqNo();
        output.setBatchId(target.getId());
        if (req.getTargetSeqNo() != null) {
            output.setSeqNo(req.getTargetSeqNo());
        }
        output.setUpdatedAt(now);
        output.setOperator(req.getOperator());
        try {
            outputRepository.save(output);
            outputRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BizException("归属更正失败：目标批次该大板已有有效产出归属，请刷新后核对");
        }
        String toContent = "产出 #" + output.getId()
                + (output.getSlabId() == null ? "" : "（大板 " + slabCode(output.getSlabId()) + "）")
                + "：现归属 " + target.getBatchNo() + " 第 " + output.getSeqNo() + " 序";

        diff.setStatus("CORRECTED");
        diff.setReason(reason);
        diff.setResolvedBy(req.getOperator());
        diff.setResolvedAt(now);
        diffRepository.save(diff);
        addAudit(sourceBatchId, output.getId(), "CORRECT_OUTPUT", fromContent, toContent,
                reason, req.getOperator(), now);
        addAudit(target.getId(), output.getId(), "CORRECT_OUTPUT", fromContent, toContent,
                reason, req.getOperator(), now);

        recomputeOpenDiffs(sourceBatch, req.getOperator(), "产出 #" + output.getId() + " 转出至 "
                + target.getBatchNo() + " 第 " + output.getSeqNo() + " 序：" + reason);
        recomputeOpenDiffs(target, req.getOperator(), "接收产出 #" + output.getId()
                + "（自 " + sourceBatch.getBatchNo() + " 第 " + oldSeq + " 序更正）：" + reason);
    }

    /** 作废：仅用于登记错误且未被引用的产出；已被后续批次作为返切来源引用的不得直接作废。 */
    private void doCorrectVoid(SawDiscrepancy diff, SawBatch batch, SawDiffResolveReq req,
                               String reason, LocalDateTime now) {
        Long outputId = req.getOutputId() != null ? req.getOutputId() : diff.getOutputId();
        SawOutput output = outputId == null ? null : outputRepository.findById(outputId).orElse(null);
        if (output == null || !"ACTIVE".equals(output.getStatus())) {
            throw new BizException("要作废的产出不存在或已作废");
        }
        if (!output.getBatchId().equals(batch.getId())) {
            throw new BizException("该产出不属于差异所在批次 " + batch.getBatchNo());
        }
        if (output.getSlabId() != null
                && !outputRepository.listBySourceSlab(output.getSlabId()).isEmpty()) {
            List<SawOutput> refs = outputRepository.listBySourceSlab(output.getSlabId());
            String where = refs.stream().map(r -> {
                SawBatch rb = batchRepository.findById(r.getBatchId()).orElse(null);
                return rb == null ? ("#" + r.getId()) : rb.getBatchNo();
            }).distinct().collect(Collectors.joining("、"));
            throw new BizException("该产出已被后续批次作为返切余料来源引用（" + where
                    + "），不能直接作废；请改用归属更正，先解除引用");
        }
        String fromContent = describeOutput(output);
        output.setStatus("VOID");
        output.setUpdatedAt(now);
        output.setOperator(req.getOperator());
        outputRepository.save(output);

        diff.setStatus("CORRECTED");
        diff.setReason(reason);
        diff.setResolvedBy(req.getOperator());
        diff.setResolvedAt(now);
        diffRepository.save(diff);
        addAudit(batch.getId(), output.getId(), "VOID_OUTPUT", fromContent,
                "产出 #" + output.getId() + " 已作废（记录保留，大板唯一归属随之释放）",
                reason, req.getOperator(), now);
        recomputeOpenDiffs(batch, req.getOperator(), "作废产出 #" + output.getId() + "：" + reason);
    }

    // ============================== 收口 ==============================

    @Transactional
    public SawBatchView close(Long batchId, SawCloseReq req) {
        // 悲观行锁：两人同时收口 / 重复点击在此串行，后到者拿到锁时批次已是 CLOSED
        SawBatch batch = batchRepository.lockById(batchId);
        if (batch == null) {
            throw new BizException("锯切批次不存在：id=" + batchId);
        }
        if ("CLOSED".equals(batch.getStatus())) {
            throw new ConflictException("批次 " + batch.getBatchNo() + " 已由 "
                    + batch.getClosedBy() + " 于 " + batch.getClosedAt()
                    + " 收口，重复收口不会形成第二个结果", "sawBatch", conflictPayload(batch));
        }
        if (batch.getHandoverAt() == null) {
            throw new BizException("班组还没完成交接，不能收口");
        }
        long openDiffs = diffRepository.countOpenByBatch(batchId);
        if (openDiffs > 0) {
            throw new BizException("还有 " + openDiffs + " 条差异未核清，批次不能收口（差异不阻断交接，但阻断收口与工单计数）");
        }
        CuttingOrder order = orderRepository.lockById(batch.getOrderId());
        if (order == null) {
            throw new BizException("批次对应工单不存在，不能收口");
        }

        // —— 一次性固化谱系计数 ——
        List<SawOutput> outputs = outputRepository.listActiveByBatch(batchId);
        int finished = 0;
        int remnant = 0;
        int sawLoss = 0;
        int qualified = 0;
        for (SawOutput o : outputs) {
            switch (o.getOutputType()) {
                case "FINISHED" -> {
                    finished++;
                    if (!"DEFECT".equals(o.getGrade())) {
                        qualified++;
                    }
                }
                case "REMNANT" -> remnant++;
                case "SAW_LOSS" -> sawLoss++;
                default -> { }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        batch.setFinishedCount(finished);
        batch.setRemnantCount(remnant);
        batch.setSawLossCount(sawLoss);
        batch.setQualifiedCount(qualified);
        batch.setStatus("CLOSED");
        batch.setClosedBy(req == null ? null : req.getClosedBy());
        batch.setClosedAt(now);
        batchRepository.save(batch);

        // —— 按合格实产推进工单：完成数 = 基线 + Σ 已收口批次合格实产（未收口批次一律不计） ——
        int baseline = order.getSawBaselineCount() == null ? 0 : order.getSawBaselineCount();
        int closedQualified = 0;
        for (SawBatch b : batchRepository.listByOrder(order.getId())) {
            if ("CLOSED".equals(b.getStatus())) {
                closedQualified += b.getQualifiedCount() == null ? 0 : b.getQualifiedCount();
            }
        }
        int newDone = baseline + closedQualified;
        order.setDoneCount(newDone);
        if (newDone >= (order.getPlanCount() == null ? 0 : order.getPlanCount())) {
            if (!"SETTLED".equals(order.getStatus())) {
                order.setStatus("DONE");
            }
            if (order.getFinishDate() == null) {
                order.setFinishDate(LocalDate.now());
            }
            if (order.getStartDate() == null) {
                order.setStartDate(LocalDate.now());
            }
        } else if ("PENDING".equals(order.getStatus())) {
            order.setStatus("PROCESSING");
        }
        orderRepository.save(order);

        // 荒料：该荒料已没有未收口批次且工单按实产做完，才置已完料（中途失败整体回滚，不会半成）
        boolean blockStillOpen = batchRepository.listByBlock(batch.getBlockId()).stream()
                .anyMatch(b -> !"CLOSED".equals(b.getStatus()));
        if (!blockStillOpen && newDone >= (order.getPlanCount() == null ? 0 : order.getPlanCount())) {
            Block block = blockRepository.findById(batch.getBlockId()).orElse(null);
            if (block != null && !"ARCHIVED".equals(block.getStatus())) {
                block.setStatus("DONE");
                blockRepository.save(block);
            }
        }

        addAudit(batchId, null, "CLOSE_BATCH", null,
                "收口固化：成品 " + finished + " / 余料 " + remnant + " / 锯耗 " + sawLoss
                        + "，合格实产 " + qualified + "；工单 " + order.getOrderNo()
                        + " 完成数 = 基线 " + baseline + " + 已收口合格实产 " + closedQualified
                        + " = " + newDone + "（计划 " + order.getPlanCount() + "）",
                null, req == null ? null : req.getClosedBy(), now);
        return detail(batchId);
    }

    // ============================== 差异比对（计划 vs 实产） ==============================

    /**
     * 重算批次的 OPEN 差异：先删全部 OPEN，再按冻结计划逐条比对生成。
     * 已 CONFIRMED / CORRECTED 的差异键会被抑制（负责人确认过的不再冒出来），历史记录保留。
     * 生成后按「是否还有 OPEN 差异」刷新批次状态：未交接→OPEN；已交接无 OPEN→READY；否则 PENDING_RECONCILE。
     */
    private List<SawDiscrepancy> recomputeOpenDiffs(SawBatch batch, String operator, String reason) {
        Long batchId = batch.getId();
        for (SawDiscrepancy open : diffRepository.listOpenByBatch(batchId)) {
            diffRepository.delete(open);
        }
        // 已核清差异键（抑制 + 历史保留）
        Set<String> resolvedKeys = new HashSet<>();
        for (SawDiscrepancy d : diffRepository.listByBatch(batchId)) {
            if (!"OPEN".equals(d.getStatus())) {
                resolvedKeys.add(diffKey(d.getDiffType(), d.getSeqNo(), d.getOutputId()));
                resolvedKeys.add(diffKey(d.getDiffType(), d.getSeqNo(), null));
            }
        }

        List<SawBatchItem> items = batchRepository.listItems(batchId);
        List<SawOutput> outputs = outputRepository.listActiveByBatch(batchId);
        Map<Integer, List<SawOutput>> bySeq = new LinkedHashMap<>();
        List<SawOutput> sawLosses = new ArrayList<>();
        for (SawOutput o : outputs) {
            if ("SAW_LOSS".equals(o.getOutputType())) {
                sawLosses.add(o);
            }
            if (o.getSeqNo() != null) {
                bySeq.computeIfAbsent(o.getSeqNo(), k -> new ArrayList<>()).add(o);
            }
        }
        Set<Integer> plannedSeqs = items.stream().map(SawBatchItem::getSeqNo).collect(Collectors.toSet());

        List<SawDiscrepancy> generated = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (SawBatchItem item : items) {
            int seq = item.getSeqNo();
            List<SawOutput> at = bySeq.getOrDefault(seq, List.of());
            List<SawOutput> physical = at.stream().filter(o -> o.getSlabId() != null).collect(Collectors.toList());
            if (at.isEmpty()) {
                addDiffUnlessResolved(generated, batchId, null, "MISSING_SEQ", seq,
                        "第" + seq + "序无任何有效产出登记（计划 " + item.getPlanWidth() + "×"
                                + item.getPlanHeight() + "cm），请核实实物去向或登记锯耗",
                        resolvedKeys, now);
                continue;
            }
            if (physical.size() > 1) {
                String codes = physical.stream().map(o -> slabCode(o.getSlabId()))
                        .collect(Collectors.joining("、"));
                addDiffUnlessResolved(generated, batchId, physical.get(0).getId(), "DUP_SEQ", seq,
                        "第" + seq + "序登记了 " + physical.size() + " 片实物产出（" + codes
                                + "），同一顺序号重复，请更正归属或作废", resolvedKeys, now);
            }
            for (SawOutput o : physical) {
                int dl = Math.abs(o.getActualLength() - item.getPlanWidth());
                int dw = Math.abs(o.getActualWidth() - item.getPlanHeight());
                if (dl > StoneDict.SIZE_TOLERANCE_CM || dw > StoneDict.SIZE_TOLERANCE_CM) {
                    addDiffUnlessResolved(generated, batchId, o.getId(), "SIZE_TOLERANCE", seq,
                            "第" + seq + "序实际 " + o.getActualLength() + "×" + o.getActualWidth()
                                    + "cm，计划 " + item.getPlanWidth() + "×" + item.getPlanHeight()
                                    + "cm：偏差 " + signed(o.getActualLength() - item.getPlanWidth()) + "/"
                                    + signed(o.getActualWidth() - item.getPlanHeight())
                                    + "cm，超过 ±" + StoneDict.SIZE_TOLERANCE_CM + "cm 允差",
                            resolvedKeys, now);
                }
            }
        }

        // 成品落在冻结计划顺序之外才是数量不符（余料 REMNANT 合法地不占计划顺序号）
        List<SawOutput> extra = new ArrayList<>();
        for (SawOutput o : outputs) {
            if ("FINISHED".equals(o.getOutputType()) && o.getSlabId() != null
                    && (o.getSeqNo() == null || !plannedSeqs.contains(o.getSeqNo()))) {
                extra.add(o);
            }
        }
        if (!extra.isEmpty()) {
            String desc = extra.stream().map(o -> slabCode(o.getSlabId())
                    + "(第" + (o.getSeqNo() == null ? "未填" : o.getSeqNo()) + "序)")
                    .collect(Collectors.joining("、"));
            SawOutput first = extra.get(0);
            addDiffUnlessResolved(generated, batchId, first.getId(), "COUNT_MISMATCH", first.getSeqNo(),
                    "有 " + extra.size() + " 片实物产出没有对应的冻结计划顺序：" + desc
                            + "；计划 " + items.size() + " 序，请确认是漏排、多切还是扫错批次",
                    resolvedKeys, now);
        }

        // 锯耗未解释
        for (SawOutput loss : sawLosses) {
            if (loss.getLossNote() == null || loss.getLossNote().isBlank()) {
                addDiffUnlessResolved(generated, batchId, loss.getId(), "UNEXPLAINED_SAW_LOSS",
                        loss.getSeqNo(),
                        "第" + (loss.getSeqNo() == null ? "未填顺序" : loss.getSeqNo())
                                + "序登记为锯耗但未填写锯耗说明，无法判定属正常锯路损耗还是漏片",
                        resolvedKeys, now);
            }
        }

        // 刷新批次状态
        if (!"CLOSED".equals(batch.getStatus())) {
            if (batch.getHandoverAt() == null) {
                batch.setStatus("OPEN");
            } else {
                batch.setStatus(generated.isEmpty() ? "READY" : "PENDING_RECONCILE");
            }
            batchRepository.save(batch);
        }
        if (reason != null && !reason.isBlank()) {
            addAudit(batchId, null, "RECOMPUTE", null,
                    "重算 OPEN 差异：现存 " + generated.size() + " 条待核，批次状态="
                            + StoneDict.sawBatchStatusName(batch.getStatus()),
                    reason, operator, now);
        }
        return generated;
    }

    private void addDiffUnlessResolved(List<SawDiscrepancy> generated, Long batchId, Long outputId,
                                       String type, Integer seq, String detail,
                                       Set<String> resolvedKeys, LocalDateTime now) {
        if (resolvedKeys.contains(diffKey(type, seq, outputId))
                || resolvedKeys.contains(diffKey(type, seq, null))) {
            return;
        }
        SawDiscrepancy d = new SawDiscrepancy();
        d.setBatchId(batchId);
        d.setOutputId(outputId);
        d.setDiffType(type);
        d.setSeqNo(seq);
        d.setDetail(detail);
        d.setStatus("OPEN");
        d.setCreatedAt(now);
        diffRepository.save(d);
        generated.add(d);
    }

    private static String diffKey(String type, Integer seq, Long outputId) {
        return type + "|" + (seq == null ? "-" : seq) + "|" + (outputId == null ? "-" : outputId);
    }

    private static String signed(int v) {
        return (v > 0 ? "+" : "") + v;
    }

    // ============================== 视图组装 ==============================

    private SawBatchListItem toListItem(SawBatch batch) {
        SawBatchListItem item = new SawBatchListItem();
        item.setId(batch.getId());
        item.setBatchNo(batch.getBatchNo());
        item.setOrderId(batch.getOrderId());
        item.setSnapOrderNo(batch.getSnapOrderNo());
        item.setBlockId(batch.getBlockId());
        item.setSnapBlockCode(batch.getSnapBlockCode());
        item.setSawType(batch.getSawType());
        item.setSawTypeName(StoneDict.sawTypeName(batch.getSawType()));
        item.setTeam(batch.getTeam());
        item.setPlanCount(batch.getPlanCount());
        item.setStatus(batch.getStatus());
        item.setStatusName(StoneDict.sawBatchStatusName(batch.getStatus()));
        item.setOpenedBy(batch.getOpenedBy());
        item.setOpenedAt(batch.getOpenedAt());
        item.setHandoverAt(batch.getHandoverAt());
        item.setClosedAt(batch.getClosedAt());
        item.setItemCount(batchRepository.listItems(batch.getId()).size());
        if ("CLOSED".equals(batch.getStatus())) {
            item.setActiveFinishedCount(batch.getFinishedCount());
            item.setActiveRemnantCount(batch.getRemnantCount());
            item.setActiveSawLossCount(batch.getSawLossCount());
            item.setActiveQualifiedCount(batch.getQualifiedCount());
        } else {
            Counts c = countOutputs(batch.getId());
            item.setActiveFinishedCount(c.finished);
            item.setActiveRemnantCount(c.remnant);
            item.setActiveSawLossCount(c.sawLoss);
            item.setActiveQualifiedCount(c.qualified);
        }
        item.setOpenDiffCount((int) diffRepository.countOpenByBatch(batch.getId()));
        return item;
    }

    private SawBatchView toView(SawBatch batch) {
        SawBatchView v = new SawBatchView();
        v.setId(batch.getId());
        v.setBatchNo(batch.getBatchNo());
        v.setOrderId(batch.getOrderId());
        v.setSnapOrderNo(batch.getSnapOrderNo());
        v.setBlockId(batch.getBlockId());
        v.setSnapBlockCode(batch.getSnapBlockCode());
        v.setSawType(batch.getSawType());
        v.setSawTypeName(StoneDict.sawTypeName(batch.getSawType()));
        v.setTeam(batch.getTeam());
        v.setPlanCount(batch.getPlanCount());
        v.setStatus(batch.getStatus());
        v.setStatusName(StoneDict.sawBatchStatusName(batch.getStatus()));
        v.setOpenedBy(batch.getOpenedBy());
        v.setOpenedAt(batch.getOpenedAt());
        v.setHandoverBy(batch.getHandoverBy());
        v.setHandoverAt(batch.getHandoverAt());
        v.setClosedBy(batch.getClosedBy());
        v.setClosedAt(batch.getClosedAt());
        v.setFinishedCount(batch.getFinishedCount());
        v.setRemnantCount(batch.getRemnantCount());
        v.setSawLossCount(batch.getSawLossCount());
        v.setQualifiedCount(batch.getQualifiedCount());
        v.setRemark(batch.getRemark());

        Map<Long, Slab> slabs = slabMap();
        Map<Long, SawBatch> batchCache = new HashMap<>();
        batchCache.put(batch.getId(), batch);

        List<SawOutput> outputs = outputRepository.listByBatch(batch.getId());
        Map<Integer, List<SawOutput>> activeBySeq = new LinkedHashMap<>();
        for (SawOutput o : outputs) {
            if ("ACTIVE".equals(o.getStatus()) && o.getSeqNo() != null) {
                activeBySeq.computeIfAbsent(o.getSeqNo(), k -> new ArrayList<>()).add(o);
            }
        }
        for (SawBatchItem item : batchRepository.listItems(batch.getId())) {
            SawBatchItemView iv = new SawBatchItemView();
            iv.setId(item.getId());
            iv.setSeqNo(item.getSeqNo());
            iv.setSlabId(item.getSlabId());
            iv.setPlanWidth(item.getPlanWidth());
            iv.setPlanHeight(item.getPlanHeight());
            iv.setXPos(item.getXPos());
            iv.setYPos(item.getYPos());
            List<SawOutput> at = activeBySeq.getOrDefault(item.getSeqNo(), List.of());
            iv.setOutputCount(at.size());
            iv.setFilled(!at.isEmpty());
            iv.setSlabCodes(at.stream().filter(o -> o.getSlabId() != null)
                    .map(o -> {
                        Slab s = slabs.get(o.getSlabId());
                        return s == null ? ("#" + o.getSlabId()) : s.getSlabCode();
                    }).collect(Collectors.joining("、")));
            v.getItems().add(iv);
            if (at.isEmpty()) {
                v.getMissingSeqs().add(item.getSeqNo());
            }
        }

        for (SawOutput o : outputs) {
            v.getOutputs().add(toOutputView(o, slabs, batchCache));
        }

        int confirmed = 0;
        int corrected = 0;
        for (SawDiscrepancy d : diffRepository.listByBatch(batch.getId())) {
            SawDiffView dv = new SawDiffView();
            dv.setId(d.getId());
            dv.setBatchId(d.getBatchId());
            dv.setOutputId(d.getOutputId());
            if (d.getOutputId() != null) {
                SawOutput o = outputRepository.findById(d.getOutputId()).orElse(null);
                if (o != null && o.getSlabId() != null) {
                    Slab s = slabs.get(o.getSlabId());
                    dv.setOutputSlabCode(s == null ? null : s.getSlabCode());
                }
            }
            dv.setDiffType(d.getDiffType());
            dv.setDiffTypeName(StoneDict.diffTypeName(d.getDiffType()));
            dv.setSeqNo(d.getSeqNo());
            dv.setDetail(d.getDetail());
            dv.setStatus(d.getStatus());
            dv.setStatusName(StoneDict.diffStatusName(d.getStatus()));
            dv.setReason(d.getReason());
            dv.setResolvedBy(d.getResolvedBy());
            dv.setResolvedAt(d.getResolvedAt());
            dv.setCreatedAt(d.getCreatedAt());
            v.getDiffs().add(dv);
            if ("CONFIRMED".equals(d.getStatus())) {
                confirmed++;
            } else if ("CORRECTED".equals(d.getStatus())) {
                corrected++;
            }
        }
        v.setConfirmedDiffCount(confirmed);
        v.setCorrectedDiffCount(corrected);
        v.setOpenDiffCount((int) diffRepository.listByBatch(batch.getId()).stream()
                .filter(d -> "OPEN".equals(d.getStatus())).count());

        Map<Long, SawOutput> outputMap = outputs.stream()
                .collect(Collectors.toMap(SawOutput::getId, o -> o, (a, b) -> a));
        for (SawOutputAudit a : auditRepository.listByBatch(batch.getId())) {
            v.getAudits().add(toAuditView(a, batchCache, outputMap));
        }

        Counts c = countOutputs(batch.getId());
        v.setActiveFinishedCount(c.finished);
        v.setActiveRemnantCount(c.remnant);
        v.setActiveSawLossCount(c.sawLoss);
        v.setActiveQualifiedCount(c.qualified);
        return v;
    }

    private SawOutputView toOutputView(SawOutput o, Map<Long, Slab> slabs, Map<Long, SawBatch> batches) {
        SawOutputView v = new SawOutputView();
        v.setId(o.getId());
        v.setBatchId(o.getBatchId());
        v.setSlabId(o.getSlabId());
        if (o.getSlabId() != null) {
            Slab s = slabs.get(o.getSlabId());
            v.setSlabCode(s == null ? null : s.getSlabCode());
        }
        v.setSourceSlabId(o.getSourceSlabId());
        if (o.getSourceSlabId() != null) {
            Slab s = slabs.get(o.getSourceSlabId());
            v.setSourceSlabCode(s == null ? null : s.getSlabCode());
        }
        v.setSeqNo(o.getSeqNo());
        v.setOutputType(o.getOutputType());
        v.setOutputTypeName(StoneDict.outputTypeName(o.getOutputType()));
        v.setActualLength(o.getActualLength());
        v.setActualWidth(o.getActualWidth());
        v.setActualThickness(o.getActualThickness());
        v.setGrade(o.getGrade());
        v.setGradeName(StoneDict.gradeName(o.getGrade()));
        v.setFinish(o.getFinish());
        v.setFinishName(StoneDict.finishName(o.getFinish()));
        v.setLossNote(o.getLossNote());
        v.setStatus(o.getStatus());
        v.setStatusName(StoneDict.outputStatusName(o.getStatus()));
        v.setClientToken(o.getClientToken());
        v.setOperator(o.getOperator());
        v.setRegisteredAt(o.getRegisteredAt());
        v.setUpdatedAt(o.getUpdatedAt());
        if ("ACTIVE".equals(o.getStatus()) && o.getSeqNo() != null && o.getSlabId() != null) {
            long same = outputRepository.listActiveByBatch(o.getBatchId()).stream()
                    .filter(x -> o.getSeqNo().equals(x.getSeqNo()))
                    .filter(x -> x.getSlabId() != null && !x.getId().equals(o.getId()))
                    .count();
            v.setDuplicated(same > 0);
        }
        return v;
    }

    private SawAuditView toAuditView(SawOutputAudit a, Map<Long, SawBatch> batches,
                                     Map<Long, SawOutput> outputs) {
        SawAuditView v = new SawAuditView();
        v.setId(a.getId());
        v.setBatchId(a.getBatchId());
        SawBatch b = batches == null ? null : batches.get(a.getBatchId());
        if (b == null) {
            b = batchRepository.findById(a.getBatchId()).orElse(null);
        }
        v.setBatchNo(b == null ? null : b.getBatchNo());
        v.setOutputId(a.getOutputId());
        if (a.getOutputId() != null) {
            SawOutput o = outputs == null ? null : outputs.get(a.getOutputId());
            if (o != null && o.getSlabId() != null) {
                v.setOutputSlabCode(slabCode(o.getSlabId()));
            } else {
                SawOutput fresh = outputRepository.findById(a.getOutputId()).orElse(null);
                if (fresh != null && fresh.getSlabId() != null) {
                    v.setOutputSlabCode(slabCode(fresh.getSlabId()));
                }
            }
        }
        v.setEventType(a.getEventType());
        v.setEventTypeName(StoneDict.sawAuditEventName(a.getEventType()));
        v.setFromContent(a.getFromContent());
        v.setToContent(a.getToContent());
        v.setReason(a.getReason());
        v.setOperator(a.getOperator());
        v.setCreatedAt(a.getCreatedAt());
        return v;
    }

    // ============================== 小工具 ==============================

    private ConflictException slabOccupiedConflict(Slab slab, SawOutput winner) {
        SawBatch wb = txBridge.findBatchById(winner.getBatchId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outputId", winner.getId());
        data.put("batchId", winner.getBatchId());
        data.put("batchNo", wb == null ? null : wb.getBatchNo());
        data.put("batchStatus", wb == null ? null : wb.getStatus());
        data.put("batchStatusName", wb == null ? null : StoneDict.sawBatchStatusName(wb.getStatus()));
        data.put("slabId", winner.getSlabId());
        data.put("slabCode", slab == null ? null : slab.getSlabCode());
        data.put("seqNo", winner.getSeqNo());
        data.put("operator", winner.getOperator());
        data.put("registeredAt", winner.getRegisteredAt());
        data.put("statusName", StoneDict.outputStatusName(winner.getStatus()));
        String code = slab == null ? ("#" + winner.getSlabId()) : slab.getSlabCode();
        return new ConflictException("大板 " + code + " 已先登记在批次 "
                + (wb == null ? ("#" + winner.getBatchId()) : wb.getBatchNo())
                + "（第 " + winner.getSeqNo() + " 序，"
                + StoneDict.outputTypeName(winner.getOutputType()) + "，登记人 "
                + (winner.getOperator() == null ? "—" : winner.getOperator())
                + "）。先到记录已保留，不能静默覆盖；如确属扫错批次，请由负责人在该差异上做归属更正",
                "sawOutput", data);
    }

    private Map<String, Object> conflictPayload(SawBatch batch) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", batch.getId());
        data.put("batchNo", batch.getBatchNo());
        data.put("status", batch.getStatus());
        data.put("statusName", StoneDict.sawBatchStatusName(batch.getStatus()));
        data.put("closedBy", batch.getClosedBy());
        data.put("closedAt", batch.getClosedAt());
        data.put("qualifiedCount", batch.getQualifiedCount());
        return data;
    }

    private void addAudit(Long batchId, Long outputId, String type, String from, String to,
                          String reason, String operator, LocalDateTime when) {
        SawOutputAudit audit = new SawOutputAudit();
        audit.setBatchId(batchId);
        audit.setOutputId(outputId);
        audit.setEventType(type);
        audit.setFromContent(from);
        audit.setToContent(to);
        audit.setReason(reason);
        audit.setOperator(operator);
        audit.setCreatedAt(when == null ? LocalDateTime.now() : when);
        auditRepository.save(audit);
    }

    private String describeOutput(SawOutput o) {
        StringBuilder sb = new StringBuilder();
        sb.append("产出 #").append(o.getId())
                .append("：").append(StoneDict.outputTypeName(o.getOutputType()))
                .append("，第").append(o.getSeqNo() == null ? "未填" : o.getSeqNo()).append("序");
        if (o.getSlabId() != null) {
            sb.append("，大板 ").append(slabCode(o.getSlabId()));
        }
        if (o.getActualLength() != null) {
            sb.append("，实尺 ").append(o.getActualLength()).append("×").append(o.getActualWidth()).append("cm");
        }
        if (o.getGrade() != null) {
            sb.append("，等级 ").append(StoneDict.gradeName(o.getGrade()));
        }
        if ("SAW_LOSS".equals(o.getOutputType()) && o.getLossNote() != null) {
            sb.append("，锯耗说明：").append(o.getLossNote());
        }
        sb.append("，幂等键 ").append(o.getClientToken());
        return sb.toString();
    }

    private String slabCode(Long slabId) {
        Slab s = slabRepository.findById(slabId).orElse(null);
        return s == null ? "#" + slabId : s.getSlabCode();
    }

    private int countActive(Long batchId, String type) {
        return (int) outputRepository.listActiveByBatch(batchId).stream()
                .filter(o -> type.equals(o.getOutputType())).count();
    }

    private Counts countOutputs(Long batchId) {
        Counts c = new Counts();
        for (SawOutput o : outputRepository.listActiveByBatch(batchId)) {
            switch (o.getOutputType()) {
                case "FINISHED" -> {
                    c.finished++;
                    if (!"DEFECT".equals(o.getGrade())) {
                        c.qualified++;
                    }
                }
                case "REMNANT" -> c.remnant++;
                case "SAW_LOSS" -> c.sawLoss++;
                default -> { }
            }
        }
        return c;
    }

    private Map<Long, Slab> slabMap() {
        return slabRepository.listAll().stream()
                .collect(Collectors.toMap(Slab::getId, s -> s, (a, b) -> a));
    }

    private Map<Long, SawBatch> batchMap() {
        return batchRepository.listAll().stream()
                .collect(Collectors.toMap(SawBatch::getId, b -> b, (a, b) -> a));
    }

    private static class Counts {
        int finished;
        int remnant;
        int sawLoss;
        int qualified;
    }

    /** 工单维度的锯切聚合（未收口批次的合格实产不计入完成数）。 */
    public static class OrderSawStats {
        public boolean tracked;
        public int baseline;
        public int qualifiedClosed;
        public int batchCount;
        public int pendingBatchCount;
        public int openDiffCount;
    }
}
