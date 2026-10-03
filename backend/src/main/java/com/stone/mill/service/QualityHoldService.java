package com.stone.mill.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stone.mill.common.BizException;
import com.stone.mill.common.ConflictException;
import com.stone.mill.dto.Dtos.QualityHoldCreateReq;
import com.stone.mill.dto.Dtos.RecheckReq;
import com.stone.mill.dto.Dtos.SubmitRecheckReq;
import com.stone.mill.dto.Queries.QualityHoldQuery;
import com.stone.mill.dto.StoneDict;
import com.stone.mill.dto.Views.BlockedDeliveryView;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.dto.Views.QualityEventView;
import com.stone.mill.dto.Views.QualityHoldSummary;
import com.stone.mill.dto.Views.QualityHoldView;
import com.stone.mill.dto.Views.QualityRoundView;
import com.stone.mill.entity.Block;
import com.stone.mill.entity.Delivery;
import com.stone.mill.entity.DeliveryItem;
import com.stone.mill.entity.QualityEvent;
import com.stone.mill.entity.QualityHold;
import com.stone.mill.entity.Slab;
import com.stone.mill.repository.BlockRepository;
import com.stone.mill.repository.DeliveryRepository;
import com.stone.mill.repository.QualityHoldRepository;
import com.stone.mill.repository.SequenceRepository;
import com.stone.mill.repository.SlabRepository;

/**
 * 模块六/七：大板质量隔离与复检放行。
 *
 * <p>业务主线（全部在单事务内完成，状态变更 + 发货冻结/解除/移除 + 金额重算要么全成要么全回滚）：
 * <ol>
 *   <li>建立隔离：冻结当时大板/荒料/等级/库位/状态快照；同一大板已有未收口记录时
 *       返回 409 冲突并带上当前记录（数据库部分唯一索引兜底并发）。隔离/待复检期间大板发货冻结。</li>
 *   <li>送复检：ISOLATED → PENDING_RECHECK，追加一条留痕。</li>
 *   <li>复检（结论只追加，不覆盖最初缺陷与历次意见）：
 *       <ul>
 *         <li>PASS：→ RELEASED，发货挂起随活跃记录消失自动解除，不动原预定/发货关系；</li>
 *         <li>REWORK：状态留在 PENDING_RECHECK，只追加复检意见；</li>
 *         <li>SCRAP：→ SCRAPPED，大板收口为不可流转，并把它从【未发运】发货单移除、重算总金额；
 *             已发运 / 签收 / 结款的明细只留记录、不倒改历史。</li>
 *       </ul>
 *   </li>
 *   <li>放行后再次发现问题：新开一轮（roundNo + 1），前一轮记录完整保留。</li>
 * </ol>
 *
 * <p>并发：建立靠部分唯一索引（撞键 → ConflictException 409），流转靠 JPA 乐观锁
 * （{@link ObjectOptimisticLockingFailureException} → 409 并回带最新记录）。
 */
@Service
public class QualityHoldService {

    private final QualityHoldRepository holdRepository;
    private final SlabRepository slabRepository;
    private final BlockRepository blockRepository;
    private final DeliveryRepository deliveryRepository;
    private final SequenceRepository sequenceRepository;
    private final QualityHoldTxBridge txBridge;

    public QualityHoldService(QualityHoldRepository holdRepository, SlabRepository slabRepository,
                              BlockRepository blockRepository, DeliveryRepository deliveryRepository,
                              SequenceRepository sequenceRepository, QualityHoldTxBridge txBridge) {
        this.holdRepository = holdRepository;
        this.slabRepository = slabRepository;
        this.blockRepository = blockRepository;
        this.deliveryRepository = deliveryRepository;
        this.sequenceRepository = sequenceRepository;
        this.txBridge = txBridge;
    }

    // ---------------- 查询 ----------------

    public PageResult<QualityHoldView> page(QualityHoldQuery query) {
        Map<Long, Slab> slabMap = slabMap();
        Map<Long, Block> blockMap = blockMap();
        List<QualityHoldView> rows = holdRepository.search(query).stream()
                .map(hold -> toView(hold, slabMap, blockMap))
                .collect(Collectors.toList());
        return new PageResult<>(rows, holdRepository.count(query), query.getPage(), query.getSize());
    }

    public QualityHoldSummary summary() {
        QualityHoldSummary summary = new QualityHoldSummary();
        summary.setHoldCount(holdRepository.count(new QualityHoldQuery()));
        summary.setIsolatedCount(holdRepository.countByStatus("ISOLATED"));
        summary.setPendingRecheckCount(holdRepository.countByStatus("PENDING_RECHECK"));
        summary.setReleasedCount(holdRepository.countByStatus("RELEASED"));
        summary.setScrappedCount(holdRepository.countByStatus("SCRAPPED"));
        summary.setBlockedDeliveryCount(blockedDeliveryMap(holdRepository.findAllActive()).size());
        return summary;
    }

    public QualityHoldView detail(Long id) {
        QualityHold hold = holdRepository.findById(id)
                .orElseThrow(() -> new BizException("质量隔离记录不存在：id=" + id));
        return toView(hold, slabMap(), blockMap());
    }

    /** 大板当前未收口隔离（无则 null），大板详情 / 发货页判断冻结用。 */
    public QualityHoldView activeBySlab(Long slabId) {
        QualityHold hold = holdRepository.findActiveBySlab(slabId);
        return hold == null ? null : toView(hold, slabMap(), blockMap());
    }

    // ---------------- 建立隔离 ----------------

    @Transactional
    public QualityHoldView create(QualityHoldCreateReq req) {
        if (!StoneDict.isValidDefectType(req.getDefectType())) {
            throw new BizException("缺陷类型取值非法");
        }
        if (!StoneDict.isValidSeverity(req.getSeverity())) {
            throw new BizException("严重程度取值非法，应为 轻微 / 较重 / 严重");
        }
        if (req.getFoundStage() != null && !req.getFoundStage().isBlank()
                && !StoneDict.isValidFoundStage(req.getFoundStage())) {
            throw new BizException("发现环节取值非法");
        }
        Slab slab = slabRepository.findById(req.getSlabId())
                .orElseThrow(() -> new BizException("大板不存在"));

        // 应用层先给一次友好提示；真正的并发兜底在库的部分唯一索引
        QualityHold existing = holdRepository.findActiveBySlab(slab.getId());
        if (existing != null) {
            throw conflictWith(existing, "大板 " + slab.getSlabCode()
                    + " 已存在未收口的质量隔离记录 " + existing.getHoldNo()
                    + "（当前：" + StoneDict.holdStatusName(existing.getStatus())
                    + "），请直接处理该记录，不能重复登记");
        }

        Block block = slab.getBlockId() == null ? null
                : blockRepository.findById(slab.getBlockId()).orElse(null);

        QualityHold hold = new QualityHold();
        hold.setSlabId(slab.getId());
        hold.setRoundNo((int) holdRepository.countBySlab(slab.getId()) + 1);
        hold.setDefectType(req.getDefectType());
        hold.setSeverity(req.getSeverity());
        hold.setAffectPosition(req.getAffectPosition());
        hold.setFoundStage(req.getFoundStage());
        hold.setSiteNote(req.getSiteNote());
        hold.setDisposeAdvice(req.getDisposeAdvice());
        hold.setReporter(req.getReporter());
        hold.setStatus("ISOLATED");
        // —— 建立瞬间的基础资料快照 ——
        hold.setSnapSlabCode(slab.getSlabCode());
        hold.setSnapBlockId(slab.getBlockId());
        hold.setSnapBlockCode(block == null ? "" : block.getBlockCode());
        hold.setSnapGrade(slab.getGrade());
        hold.setSnapLocation(slab.getLocation());
        hold.setSnapSlabStatus(slab.getStatus());
        LocalDateTime now = LocalDateTime.now();
        hold.setCreatedAt(now);
        hold.setUpdatedAt(now);
        // 原子序列取号：并发两人也不会拿到相同记录号
        long seq = sequenceRepository.nextValue("QH-" + now.getYear());
        hold.setHoldNo("QH-" + now.getYear() + "-" + String.format("%04d", seq));

        try {
            holdRepository.save(hold);
            // 立即 flush，让部分唯一索引冲突在业务代码处抛出（而不是拖到事务提交被全局兜底吞成 500）
            holdRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 同一大板并发：部分唯一索引只放行一条；用新事务读出冲突方记录回带给失败方
            QualityHold winner = txBridge.findActive(slab.getId());
            if (winner != null) {
                throw new ConflictException("大板 " + slab.getSlabCode()
                        + " 刚被其他人建立了隔离记录 " + winner.getHoldNo()
                        + "，页面已为你刷新到该记录", "hold", toViewSafe(winner));
            }
            throw new BizException("登记失败：数据冲突，请刷新后重试");
        }

        addEvent(hold.getId(), "CREATE", null, "ISOLATED", null, buildCreateContent(req),
                req.getReporter(), now);
        return toView(hold, slabMap(), blockMap());
    }

    // ---------------- 送复检 ----------------

    @Transactional
    public QualityHoldView submitRecheck(Long id, SubmitRecheckReq req) {
        QualityHold hold = requireActive(id);
        if (!"ISOLATED".equals(hold.getStatus())) {
            // 已被推进到待复检（可能是对方刚操作），返回 409 让前端刷新到最新
            throw staleConflict(id, "该记录已处于「待复检」，可能刚被其他人送复检，页面已刷新到最新状态");
        }
        String from = hold.getStatus();
        hold.setStatus("PENDING_RECHECK");
        hold.setUpdatedAt(LocalDateTime.now());
        try {
            holdRepository.save(hold);
            holdRepository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw staleConflict(id, "该隔离记录刚被其他人处理过，已刷新到最新状态，请重试");
        }
        addEvent(id, "SUBMIT_RECHECK", from, "PENDING_RECHECK", null,
                req == null ? null : req.getNote(), req == null ? null : req.getOperator(), LocalDateTime.now());
        return detail(id);
    }

    // ---------------- 复检结论（放行 / 返工观察 / 报废） ----------------

    @Transactional
    public QualityHoldView recheck(Long id, RecheckReq req) {
        if (req == null || !StoneDict.isValidReconcileResult(req.getConclusion())) {
            throw new BizException("复检结论取值非法：PASS 合格放行 / REWORK 返工观察 / SCRAP 报废");
        }
        QualityHold hold = requireActive(id);
        if (!"PENDING_RECHECK".equals(hold.getStatus())) {
            throw staleConflict(id, "该记录当前不是「待复检」，可能刚被其他人处理，页面已刷新到最新状态");
        }
        LocalDateTime now = LocalDateTime.now();
        String operator = req.getOperator();

        switch (req.getConclusion()) {
            case "PASS" -> {
                String from = hold.getStatus();
                hold.setStatus("RELEASED");
                hold.setClosedRemark(req.getOpinion());
                hold.setClosedBy(operator);
                hold.setClosedAt(now);
                hold.setUpdatedAt(now);
                saveClosedHold(hold);
                // 放行：留痕 RELEASE。发货挂起因活跃记录消失而自动解除（见 DeliveryService 的派生判断），
                // 不删除任何发货明细、不改大板 RESERVED 状态——原预定关系原样保留。
                addEvent(id, "RELEASE", from, "RELEASED", "PASS", req.getOpinion(), operator, now);
            }
            case "REWORK" -> {
                // 返工观察：状态不变，仅追加一条复检意见（历次意见都保留）
                hold.setUpdatedAt(now);
                saveClosedHold(hold);
                addEvent(id, "RECHECK", "PENDING_RECHECK", "PENDING_RECHECK", "REWORK",
                        req.getOpinion(), operator, now);
            }
            case "SCRAP" -> doScrap(hold, req.getOpinion(), operator, now, false);
            default -> throw new BizException("复检结论取值非法");
        }
        return detail(id);
    }

    // ---------------- 直接判废（隔离中也可由有权人员判废，仍走留痕） ----------------

    @Transactional
    public QualityHoldView scrap(Long id, RecheckReq req) {
        QualityHold hold = requireActive(id);
        LocalDateTime now = LocalDateTime.now();
        String operator = req == null ? null : req.getOperator();
        String opinion = req == null ? null : req.getOpinion();
        String from = hold.getStatus();
        // 隔离中直接判废：先补一条送复检动作，再判废，保证时间线完整
        if ("ISOLATED".equals(from)) {
            hold.setStatus("PENDING_RECHECK");
            hold.setUpdatedAt(now);
            saveClosedHold(hold);
            addEvent(id, "SUBMIT_RECHECK", from, "PENDING_RECHECK", null,
                    "质检现场直接评定，转待复检后判废", operator, now);
        }
        doScrap(hold, opinion, operator, now, true);
        return detail(id);
    }

    /**
     * 报废收口（与复检结论 SCRAP 共用）：
     * 记录 → SCRAPPED；大板 → SCRAP 不可再流转；
     * 从【未发运】发货单（LOADING）移除该板明细并重算总金额；
     * 已发运 / 签收 / 结款的单只留记录、不倒改。
     */
    private void doScrap(QualityHold hold, String opinion, String operator, LocalDateTime now, boolean direct) {
        String from = hold.getStatus();
        hold.setStatus("SCRAPPED");
        hold.setClosedRemark(opinion);
        hold.setClosedBy(operator);
        hold.setClosedAt(now);
        hold.setUpdatedAt(now);
        saveClosedHold(hold);
        addEvent(hold.getId(), "SCRAP", from, "SCRAPPED", "SCRAP", opinion, operator, now);

        Slab slab = slabRepository.findById(hold.getSlabId()).orElse(null);
        if (slab != null && !"SCRAP".equals(slab.getStatus())) {
            slab.setStatus("SCRAP");
            slabRepository.save(slab);
        }

        // 从未发运（待装车）发货单移除明细并重算；其余历史单不动
        Set<Long> recalcDeliveryIds = new HashSet<>();
        for (DeliveryItem item : deliveryRepository.findItemsBySlab(hold.getSlabId())) {
            Delivery delivery = deliveryRepository.findById(item.getDeliveryId()).orElse(null);
            if (delivery != null && "LOADING".equals(delivery.getStatus())) {
                deliveryRepository.deleteItem(item.getId());
                recalcDeliveryIds.add(delivery.getId());
            }
        }
        for (Long deliveryId : recalcDeliveryIds) {
            Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
            if (delivery != null) {
                recalcTotal(delivery);
            }
        }
    }

    private void saveClosedHold(QualityHold hold) {
        try {
            holdRepository.save(hold);
            holdRepository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw staleConflict(hold.getId(), "该隔离记录刚被其他人复检处理，已刷新到最新状态，请确认后再操作");
        }
    }

    // ---------------- 发货派生冻结（供 DeliveryService 查询） ----------------

    /**
     * 当前未收口隔离按大板 id 成图：发货页据此派生「明细是否挂起 / 发货单是否禁运」。
     * 放行时记录收口，下一次查询自动没有该板——挂起解除与状态变更天然是一个一致结果，
     * 不会出现「已放行但仍挂起」。
     */
    public Map<Long, QualityHold> activeHoldBySlabMap() {
        Map<Long, QualityHold> map = new HashMap<>();
        for (QualityHold hold : holdRepository.findAllActive()) {
            map.put(hold.getSlabId(), hold);
        }
        return map;
    }

    /** 被挂起禁运的发货单 id → 命中的隔离记录集合（仅待装车单会被禁运）。 */
    public Map<Long, List<QualityHold>> blockedDeliveryMap(List<QualityHold> activeHolds) {
        Map<Long, List<QualityHold>> result = new HashMap<>();
        if (activeHolds == null || activeHolds.isEmpty()) {
            return result;
        }
        List<Long> slabIds = activeHolds.stream().map(QualityHold::getSlabId).toList();
        Map<Long, QualityHold> bySlab = activeHolds.stream()
                .collect(Collectors.toMap(QualityHold::getSlabId, h -> h, (a, b) -> a));
        for (DeliveryItem item : deliveryRepository.findItemsBySlabIds(slabIds)) {
            Delivery delivery = deliveryRepository.findById(item.getDeliveryId()).orElse(null);
            if (delivery != null && "LOADING".equals(delivery.getStatus())) {
                result.computeIfAbsent(delivery.getId(), k -> new ArrayList<>())
                        .add(bySlab.get(item.getSlabId()));
            }
        }
        return result;
    }

    // ---------------- 内部工具 ----------------

    private QualityHold requireActive(Long id) {
        QualityHold hold = holdRepository.findById(id)
                .orElseThrow(() -> new BizException("质量隔离记录不存在：id=" + id));
        if (!StoneDict.isHoldActive(hold.getStatus())) {
            throw new BizException("该记录已收口为「" + StoneDict.holdStatusName(hold.getStatus())
                    + "」，不能再流转");
        }
        return hold;
    }

    /**
     * 冲突回带：事务可能已被标记 rollback-only，不能再用当前持久化上下文做拼装，
     * 只放 {id, holdNo, slabId, status, statusName}，前端据此 id 再拉详情刷新到最新。
     */
    private ConflictException staleConflict(Long holdId, String message) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        QualityHold latest = txBridge.findById(holdId);
        if (latest != null) {
            data.put("id", latest.getId());
            data.put("holdNo", latest.getHoldNo());
            data.put("slabId", latest.getSlabId());
            data.put("status", latest.getStatus());
            data.put("statusName", StoneDict.holdStatusName(latest.getStatus()));
        }
        return new ConflictException(message, "hold", data);
    }

    /** 建立前的预检冲突（事务健康）：可以直接回带完整视图。 */
    private ConflictException conflictWith(QualityHold hold, String message) {
        return new ConflictException(message, "hold", toView(hold, slabMap(), blockMap()));
    }

    /** 唯一键冲突后（当前事务必回滚）：用新事务读到的实体组最小冲突包，不在脏上下文里查询。 */
    private java.util.Map<String, Object> toViewSafe(QualityHold hold) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("id", hold.getId());
        data.put("holdNo", hold.getHoldNo());
        data.put("slabId", hold.getSlabId());
        data.put("snapSlabCode", hold.getSnapSlabCode());
        data.put("status", hold.getStatus());
        data.put("statusName", StoneDict.holdStatusName(hold.getStatus()));
        return data;
    }

    private void addEvent(Long holdId, String type, String from, String to,
                          String conclusion, String content, String operator, LocalDateTime when) {
        QualityEvent event = new QualityEvent();
        event.setHoldId(holdId);
        event.setEventType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setConclusion(conclusion);
        event.setContent(content);
        event.setOperator(operator);
        event.setCreatedAt(when == null ? LocalDateTime.now() : when);
        holdRepository.saveEvent(event);
    }

    private String buildCreateContent(QualityHoldCreateReq req) {
        StringBuilder sb = new StringBuilder();
        if (req.getSiteNote() != null && !req.getSiteNote().isBlank()) {
            sb.append(req.getSiteNote());
        }
        if (req.getDisposeAdvice() != null && !req.getDisposeAdvice().isBlank()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("处置建议：").append(req.getDisposeAdvice());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 回写发货单总金额（与 DeliveryService 同口径：明细金额之和）。 */
    private void recalcTotal(Delivery delivery) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (DeliveryItem item : deliveryRepository.listItems(delivery.getId())) {
            total = total.add(item.getAmount() == null ? java.math.BigDecimal.ZERO : item.getAmount());
        }
        delivery.setTotalAmount(total.setScale(2, java.math.RoundingMode.HALF_UP));
        deliveryRepository.save(delivery);
    }

    private Map<Long, Slab> slabMap() {
        return slabRepository.listAll().stream()
                .collect(Collectors.toMap(Slab::getId, s -> s, (a, b) -> a));
    }

    private Map<Long, Block> blockMap() {
        return blockRepository.listAll().stream()
                .collect(Collectors.toMap(Block::getId, b -> b, (a, b) -> a));
    }

    private QualityHoldView toView(QualityHold hold, Map<Long, Slab> slabs, Map<Long, Block> blocks) {
        QualityHoldView view = new QualityHoldView();
        view.setId(hold.getId());
        view.setHoldNo(hold.getHoldNo());
        view.setSlabId(hold.getSlabId());
        view.setRoundNo(hold.getRoundNo());
        view.setDefectType(hold.getDefectType());
        view.setDefectTypeName(StoneDict.defectTypeName(hold.getDefectType()));
        view.setSeverity(hold.getSeverity());
        view.setSeverityName(StoneDict.severityName(hold.getSeverity()));
        view.setAffectPosition(hold.getAffectPosition());
        view.setFoundStage(hold.getFoundStage());
        view.setFoundStageName(StoneDict.foundStageName(hold.getFoundStage()));
        view.setSiteNote(hold.getSiteNote());
        view.setDisposeAdvice(hold.getDisposeAdvice());
        view.setReporter(hold.getReporter());
        view.setStatus(hold.getStatus());
        view.setStatusName(StoneDict.holdStatusName(hold.getStatus()));
        view.setActive(StoneDict.isHoldActive(hold.getStatus()));
        view.setVersion(hold.getVersion());
        view.setCreatedAt(hold.getCreatedAt());
        view.setUpdatedAt(hold.getUpdatedAt());
        view.setClosedRemark(hold.getClosedRemark());
        view.setClosedBy(hold.getClosedBy());
        view.setClosedAt(hold.getClosedAt());

        // 快照
        view.setSnapSlabCode(hold.getSnapSlabCode());
        view.setSnapBlockCode(hold.getSnapBlockCode());
        view.setSnapBlockId(hold.getSnapBlockId());
        view.setSnapGrade(hold.getSnapGrade());
        view.setSnapGradeName(StoneDict.gradeName(hold.getSnapGrade()));
        view.setSnapLocation(hold.getSnapLocation());
        view.setSnapSlabStatus(hold.getSnapSlabStatus());
        view.setSnapSlabStatusName(StoneDict.slabStatusName(hold.getSnapSlabStatus()));

        // 当前资料（与快照并存，互不覆盖）
        Slab slab = slabs.get(hold.getSlabId());
        if (slab != null) {
            view.setCurrentSlabCode(slab.getSlabCode());
            view.setCurrentGrade(slab.getGrade());
            view.setCurrentGradeName(StoneDict.gradeName(slab.getGrade()));
            view.setCurrentLocation(slab.getLocation());
            view.setCurrentSlabStatus(slab.getStatus());
            view.setCurrentSlabStatusName(StoneDict.slabStatusName(slab.getStatus()));
            view.setSlabScrapped("SCRAP".equals(slab.getStatus()));
        }

        // 当前阻断的待装车发货单（放行 / 报废收口后为空）
        if (StoneDict.isHoldActive(hold.getStatus())) {
            for (DeliveryItem item : deliveryRepository.findItemsBySlab(hold.getSlabId())) {
                Delivery delivery = deliveryRepository.findById(item.getDeliveryId()).orElse(null);
                if (delivery != null && "LOADING".equals(delivery.getStatus())) {
                    BlockedDeliveryView bd = new BlockedDeliveryView();
                    bd.setDeliveryId(delivery.getId());
                    bd.setDeliveryNo(delivery.getDeliveryNo());
                    bd.setCustomer(delivery.getCustomer());
                    bd.setStatus(delivery.getStatus());
                    bd.setStatusName(StoneDict.deliveryStatusName(delivery.getStatus()));
                    bd.setItemAmount(item.getAmount());
                    view.getBlockedDeliveries().add(bd);
                }
            }
        }

        // 同板历轮
        for (QualityHold round : holdRepository.findRoundsBySlab(hold.getSlabId())) {
            QualityRoundView rv = new QualityRoundView();
            rv.setId(round.getId());
            rv.setHoldNo(round.getHoldNo());
            rv.setRoundNo(round.getRoundNo());
            rv.setStatus(round.getStatus());
            rv.setStatusName(StoneDict.holdStatusName(round.getStatus()));
            rv.setDefectTypeName(StoneDict.defectTypeName(round.getDefectType()));
            rv.setSeverityName(StoneDict.severityName(round.getSeverity()));
            rv.setCreatedAt(round.getCreatedAt());
            rv.setClosedAt(round.getClosedAt());
            view.getRounds().add(rv);
        }

        // 时间线
        for (QualityEvent event : holdRepository.listEvents(hold.getId())) {
            view.getEvents().add(toEventView(event));
        }
        return view;
    }

    private QualityEventView toEventView(QualityEvent event) {
        QualityEventView v = new QualityEventView();
        v.setId(event.getId());
        v.setHoldId(event.getHoldId());
        v.setEventType(event.getEventType());
        v.setEventTypeName(StoneDict.holdEventName(event.getEventType()));
        v.setFromStatus(event.getFromStatus());
        v.setFromStatusName(StoneDict.holdStatusName(event.getFromStatus()));
        v.setToStatus(event.getToStatus());
        v.setToStatusName(StoneDict.holdStatusName(event.getToStatus()));
        v.setConclusion(event.getConclusion());
        v.setConclusionName(StoneDict.reconcileResultName(event.getConclusion()));
        v.setContent(event.getContent());
        v.setOperator(event.getOperator());
        v.setCreatedAt(event.getCreatedAt());
        return v;
    }
}
