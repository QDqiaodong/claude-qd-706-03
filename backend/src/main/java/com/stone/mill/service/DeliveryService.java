package com.stone.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stone.mill.common.BizException;
import com.stone.mill.dto.Dtos.DeliveryItemReq;
import com.stone.mill.dto.Dtos.DeliverySaveReq;
import com.stone.mill.dto.Dtos.StatusReq;
import com.stone.mill.dto.Queries.DeliveryQuery;
import com.stone.mill.dto.StoneDict;
import com.stone.mill.dto.Views.DeliveryItemView;
import com.stone.mill.dto.Views.DeliverySummary;
import com.stone.mill.dto.Views.DeliveryView;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.entity.Delivery;
import com.stone.mill.entity.DeliveryItem;
import com.stone.mill.entity.QualityHold;
import com.stone.mill.entity.Slab;
import com.stone.mill.repository.DeliveryRepository;
import com.stone.mill.repository.SlabRepository;

/**
 * 模块五：出库发货。
 *
 * <p>规则面：
 * <ol>
 *   <li>发货单号唯一；客户不能为空</li>
 *   <li>明细只能挂在可发货的大板（在库 / 已预定）上，次品不允许发货</li>
 *   <li>金额 = 数量 × 面积 × 单价，保存明细后回写发货单总金额</li>
 *   <li>只有待装车发货单允许增补大板明细；已发运 / 已签收 / 已结款页面与接口都拒绝增补，
 *       拒绝后原明细、总金额、大板库存状态保持不变（历史发货单不被改写）</li>
 *   <li>状态流转：待装车 → 已发运 → 已签收 → 已结款，签收后倒推大板为已出库</li>
 * </ol>
 */
@Service
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final SlabRepository slabRepository;
    private final QualityHoldService qualityHoldService;

    public DeliveryService(DeliveryRepository deliveryRepository, SlabRepository slabRepository,
                           QualityHoldService qualityHoldService) {
        this.deliveryRepository = deliveryRepository;
        this.slabRepository = slabRepository;
        this.qualityHoldService = qualityHoldService;
    }

    public PageResult<DeliveryView> page(DeliveryQuery query) {
        Map<Long, QualityHold> holdMap = qualityHoldService.activeHoldBySlabMap();
        List<DeliveryView> rows = deliveryRepository.search(query).stream()
                .map(delivery -> toView(delivery, holdMap)).collect(Collectors.toList());
        return new PageResult<>(rows, deliveryRepository.count(query),
                query.getPage(), query.getSize());
    }

    public DeliverySummary summary(DeliveryQuery query) {
        DeliverySummary summary = deliveryRepository.summarize(query);
        summary.setBlockedCount(qualityHoldService
                .blockedDeliveryMap(holdList()).size());
        return summary;
    }

    public DeliveryView detail(Long id) {
        return toView(require(id), qualityHoldService.activeHoldBySlabMap());
    }

    public List<DeliveryItemView> items(Long deliveryId) {
        require(deliveryId);
        Map<Long, Slab> slabs = slabMap();
        Map<Long, QualityHold> holdMap = qualityHoldService.activeHoldBySlabMap();
        return deliveryRepository.listItems(deliveryId).stream()
                .map(item -> toItemView(item, slabs, holdMap))
                .collect(Collectors.toList());
    }

    @Transactional
    public DeliveryView create(DeliverySaveReq req) {
        if (deliveryRepository.findByNo(req.getDeliveryNo()) != null) {
            throw new BizException("发货单号 " + req.getDeliveryNo() + " 已存在");
        }
        Delivery delivery = new Delivery();
        delivery.setDeliveryNo(req.getDeliveryNo());
        delivery.setCustomer(req.getCustomer());
        delivery.setContact(req.getContact());
        delivery.setPhone(req.getPhone());
        delivery.setPlateNo(req.getPlateNo());
        delivery.setDriver(req.getDriver());
        delivery.setDeliveryDate(req.getDeliveryDate() == null ? LocalDate.now() : req.getDeliveryDate());
        delivery.setStatus("LOADING");
        delivery.setRemark(req.getRemark());
        delivery.setTotalAmount(BigDecimal.ZERO);
        deliveryRepository.save(delivery);
        return toView(delivery, qualityHoldService.activeHoldBySlabMap());
    }

    /**
     * 加大板明细：只有「待装车」允许增补；已发运 / 已签收 / 已结款一律拒绝。
     * 校验可发货状态、质量冻结、算金额、回写总金额；拒绝时不写任何数据，
     * 原明细、总金额、大板库存状态均保持不变。
     */
    @Transactional
    public DeliveryItemView addItem(Long deliveryId, DeliveryItemReq req) {
        Delivery delivery = requireForUpdate(deliveryId);
        if (!"LOADING".equals(delivery.getStatus())) {
            throw new BizException("发货单 " + delivery.getDeliveryNo() + " 当前状态为「"
                    + StoneDict.deliveryStatusName(delivery.getStatus())
                    + "」，只有待装车的发货单才能加大板；已发运 / 已签收 / 已结款的历史单据明细与金额不可再改");
        }
        Slab slab = slabRepository.findById(req.getSlabId()).orElseThrow(() -> new BizException("大板不存在"));
        if (!StoneDict.SHIPPABLE_STATUS.contains(slab.getStatus())) {
            throw new BizException("大板 " + slab.getSlabCode() + " 当前状态为「"
                    + StoneDict.slabStatusName(slab.getStatus()) + "」，不可发货");
        }
        if ("DEFECT".equals(slab.getGrade())) {
            throw new BizException("次品大板不允许发货");
        }
        // 质量冻结：隔离中 / 待复检的大板不能再被选入任何发货单
        if (qualityHoldService.activeHoldBySlabMap().containsKey(slab.getId())) {
            throw new BizException("大板 " + slab.getSlabCode() + " 正处于质量隔离 / 待复检，已发货冻结，不能加入发货单");
        }
        // 同一大板不允许在同一张发货单重复挂明细（断网重试 / 重复点击不应产生第二行）
        for (DeliveryItem existing : deliveryRepository.listItems(deliveryId)) {
            if (existing.getSlabId().equals(slab.getId())) {
                throw new BizException("大板 " + slab.getSlabCode()
                        + " 已在发货单 " + delivery.getDeliveryNo() + " 的明细中，不能重复加入");
            }
        }
        int quantity = req.getQuantity() == null || req.getQuantity() <= 0 ? 1 : req.getQuantity();
        BigDecimal area = slab.getAreaM2();
        DeliveryItem item = new DeliveryItem();
        item.setDeliveryId(deliveryId);
        item.setSlabId(slab.getId());
        item.setSlabCode(slab.getSlabCode());
        item.setQuantity(quantity);
        item.setAreaM2(area);
        item.setUnitPrice(req.getUnitPrice());
        item.setAmount(area.multiply(BigDecimal.valueOf(quantity))
                .multiply(req.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
        deliveryRepository.saveItem(item);

        if ("INSTOCK".equals(slab.getStatus())) {
            slab.setStatus("RESERVED");
            slabRepository.save(slab);
        }
        recalcTotal(delivery);
        return toItemView(item, slabMap(), qualityHoldService.activeHoldBySlabMap());
    }

    @Transactional
    public DeliveryView setStatus(Long id, StatusReq req) {
        if (!StoneDict.isValidDeliveryStatus(req.getStatus())) {
            throw new BizException("发货状态取值非法");
        }
        // 与 addItem 同一把行锁串行化：发运一旦提交，并发的加大板请求只能看到最新状态并被拒
        Delivery delivery = requireForUpdate(id);
        if ("PAID".equals(delivery.getStatus())) {
            throw new BizException("已结款的发货单不能再流转");
        }
        // 质量挂起：待装车 → 已发运 必须先解除挂起（挂起期间明细与金额原样保留供核对）
        boolean shippingOut = "SHIPPED".equals(req.getStatus())
                || "SIGNED".equals(req.getStatus()) || "PAID".equals(req.getStatus());
        if (shippingOut && "LOADING".equals(delivery.getStatus())) {
            List<QualityHold> blockers = blockedHoldsOf(delivery);
            if (!blockers.isEmpty()) {
                String codes = blockers.stream().map(QualityHold::getHoldNo)
                        .distinct().collect(Collectors.joining("、"));
                throw new BizException("发货单 " + delivery.getDeliveryNo()
                        + " 存在质量挂起的大板（隔离记录 " + codes
                        + "），已禁止发运；请先完成复检放行或报废移除后再操作");
            }
        }
        delivery.setStatus(req.getStatus());
        if (req.getRemark() != null) {
            delivery.setRemark(req.getRemark());
        }
        deliveryRepository.save(delivery);

        // 发运之后，单上的大板正式出库
        if ("SHIPPED".equals(req.getStatus()) || "SIGNED".equals(req.getStatus()) || "PAID".equals(req.getStatus())) {
            Map<Long, Slab> slabs = slabMap();
            for (DeliveryItem item : deliveryRepository.listItems(id)) {
                Slab slab = slabs.get(item.getSlabId());
                if (slab != null && !"OUTBOUND".equals(slab.getStatus())) {
                    slab.setStatus("OUTBOUND");
                    slabRepository.save(slab);
                }
            }
        }
        return toView(delivery, qualityHoldService.activeHoldBySlabMap());
    }

    /** 该发货单当前被质量挂起命中的明细对应的隔离记录。 */
    private List<QualityHold> blockedHoldsOf(Delivery delivery) {
        Map<Long, QualityHold> holdMap = qualityHoldService.activeHoldBySlabMap();
        List<QualityHold> hits = new ArrayList<>();
        for (DeliveryItem item : deliveryRepository.listItems(delivery.getId())) {
            QualityHold hold = holdMap.get(item.getSlabId());
            if (hold != null) {
                hits.add(hold);
            }
        }
        return hits;
    }

    /** 回写发货单总金额 = 明细金额之和。 */
    private void recalcTotal(Delivery delivery) {
        BigDecimal total = BigDecimal.ZERO;
        int quantity = 0;
        for (DeliveryItem item : deliveryRepository.listItems(delivery.getId())) {
            total = total.add(item.getAmount() == null ? BigDecimal.ZERO : item.getAmount());
            quantity += item.getQuantity() == null ? 0 : item.getQuantity();
        }
        delivery.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        deliveryRepository.save(delivery);
    }

    private Delivery require(Long id) {
        return deliveryRepository.findById(id).orElseThrow(() -> new BizException("发货单不存在：id=" + id));
    }

    /** 取发货单并持有行级写锁（写操作前调用，与状态流转 / 增补明细互斥）。 */
    private Delivery requireForUpdate(Long id) {
        return deliveryRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("发货单不存在：id=" + id));
    }

    private List<QualityHold> holdList() {
        return new ArrayList<>(qualityHoldService.activeHoldBySlabMap().values());
    }

    private Map<Long, Slab> slabMap() {
        return slabRepository.listAll().stream()
                .collect(Collectors.toMap(Slab::getId, Function.identity(), (a, b) -> a));
    }

    private DeliveryView toView(Delivery delivery, Map<Long, QualityHold> holdMap) {
        List<DeliveryItem> items = deliveryRepository.listItems(delivery.getId());
        int quantity = 0;
        BigDecimal area = BigDecimal.ZERO;
        BlockedSet blocked = collectBlocked(items, holdMap);
        for (DeliveryItem item : items) {
            quantity += item.getQuantity() == null ? 0 : item.getQuantity();
            area = area.add(item.getAreaM2() == null ? BigDecimal.ZERO : item.getAreaM2());
        }
        DeliveryView view = new DeliveryView();
        view.setId(delivery.getId());
        view.setDeliveryNo(delivery.getDeliveryNo());
        view.setCustomer(delivery.getCustomer());
        view.setContact(delivery.getContact());
        view.setPhone(delivery.getPhone());
        view.setPlateNo(delivery.getPlateNo());
        view.setDriver(delivery.getDriver());
        view.setDeliveryDate(delivery.getDeliveryDate());
        view.setTotalAmount(delivery.getTotalAmount());
        view.setStatus(delivery.getStatus());
        view.setStatusName(StoneDict.deliveryStatusName(delivery.getStatus()));
        view.setRemark(delivery.getRemark());
        view.setItemCount(items.size());
        view.setTotalQuantity(quantity);
        view.setTotalArea(area.setScale(2, RoundingMode.HALF_UP));
        // 只有待装车单上的命中才算「质量挂起禁运」；已发运的单只做展示
        boolean isBlocked = "LOADING".equals(delivery.getStatus()) && !blocked.holdNos.isEmpty();
        view.setQualityBlocked(isBlocked);
        view.setBlockedItemCount(isBlocked ? blocked.count : 0);
        view.setBlockedSlabCodes(isBlocked ? new ArrayList<>(blocked.slabCodes) : new ArrayList<>());
        view.setBlockedHoldNos(isBlocked ? new ArrayList<>(blocked.holdNos) : new ArrayList<>());
        return view;
    }

    /** 汇总明细中被未收口隔离命中的大板与隔离记录。 */
    private BlockedSet collectBlocked(List<DeliveryItem> items, Map<Long, QualityHold> holdMap) {
        BlockedSet data = new BlockedSet();
        for (DeliveryItem item : items) {
            QualityHold hold = holdMap.get(item.getSlabId());
            if (hold != null) {
                data.count++;
                data.slabCodes.add(item.getSlabCode());
                data.holdNos.add(hold.getHoldNo());
            }
        }
        return data;
    }

    /** 仅方法内传递挂起归集结果用。 */
    private static class BlockedSet {
        int count;
        final java.util.Set<String> slabCodes = new java.util.TreeSet<>();
        final java.util.Set<String> holdNos = new java.util.TreeSet<>();
    }

    private DeliveryItemView toItemView(DeliveryItem item, Map<Long, Slab> slabs,
                                        Map<Long, QualityHold> holdMap) {
        Slab slab = slabs.get(item.getSlabId());
        DeliveryItemView view = new DeliveryItemView();
        view.setId(item.getId());
        view.setDeliveryId(item.getDeliveryId());
        view.setSlabId(item.getSlabId());
        view.setSlabCode(item.getSlabCode());
        view.setQuantity(item.getQuantity());
        view.setAreaM2(item.getAreaM2());
        view.setUnitPrice(item.getUnitPrice());
        view.setAmount(item.getAmount());
        view.setGrade(slab == null ? "" : slab.getGrade());
        view.setGradeName(slab == null ? "" : StoneDict.gradeName(slab.getGrade()));
        view.setFinishName(slab == null ? "" : StoneDict.finishName(slab.getFinish()));
        view.setLengthCm(slab == null ? null : slab.getLengthCm());
        view.setWidthCm(slab == null ? null : slab.getWidthCm());
        view.setLocation(slab == null ? "" : slab.getLocation());
        QualityHold hold = holdMap.get(item.getSlabId());
        if (hold != null) {
            view.setQualityBlocked(true);
            view.setQualityHoldNo(hold.getHoldNo());
            view.setQualityHoldStatusName(StoneDict.holdStatusName(hold.getStatus()));
            view.setQualityDefectName(StoneDict.defectTypeName(hold.getDefectType()));
        }
        return view;
    }
}
