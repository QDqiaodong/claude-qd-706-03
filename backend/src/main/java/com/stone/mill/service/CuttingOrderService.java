package com.stone.mill.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stone.mill.common.BizException;
import com.stone.mill.dto.Dtos.OrderSaveReq;
import com.stone.mill.dto.Dtos.ProgressReq;
import com.stone.mill.dto.Queries.OrderQuery;
import com.stone.mill.dto.StoneDict;
import com.stone.mill.dto.Views.CuttingOrderView;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.entity.Block;
import com.stone.mill.entity.CuttingOrder;
import com.stone.mill.repository.BlockRepository;
import com.stone.mill.repository.CuttingOrderRepository;

/**
 * 模块三：加工工单。
 *
 * <p>规则面：
 * <ol>
 *   <li>工单号唯一；计划板数必须大于 0，已完成板数不得超过计划板数</li>
 *   <li>锯切方式取值必须在词典内</li>
 *   <li>开工把荒料置为加工中；完工按计划板数补齐并置荒料为已完料</li>
 *   <li>只有已完工的工单才能结算</li>
 * </ol>
 */
@Service
public class CuttingOrderService {

    private final CuttingOrderRepository orderRepository;
    private final BlockRepository blockRepository;
    private final SawBatchService sawBatchService;

    public CuttingOrderService(CuttingOrderRepository orderRepository, BlockRepository blockRepository,
                               SawBatchService sawBatchService) {
        this.orderRepository = orderRepository;
        this.blockRepository = blockRepository;
        this.sawBatchService = sawBatchService;
    }

    public PageResult<CuttingOrderView> page(OrderQuery query) {
        Map<Long, Block> blocks = blockMap();
        Map<Long, SawBatchService.OrderSawStats> sawStats = sawBatchService.orderStatsMap();
        List<CuttingOrderView> rows = orderRepository.search(query).stream()
                .map(order -> toView(order, blocks, sawStats.get(order.getId())))
                .collect(Collectors.toList());
        return new PageResult<>(rows, orderRepository.count(query),
                query.getPage(), query.getSize());
    }

    public List<CuttingOrderView> listAll() {
        Map<Long, Block> blocks = blockMap();
        Map<Long, SawBatchService.OrderSawStats> sawStats = sawBatchService.orderStatsMap();
        return orderRepository.search(new OrderQuery()).stream()
                .map(order -> toView(order, blocks, sawStats.get(order.getId())))
                .collect(Collectors.toList());
    }

    public CuttingOrderView detail(Long id) {
        return toView(require(id), blockMap(), sawBatchService.orderStats(id));
    }

    @Transactional
    public CuttingOrderView create(OrderSaveReq req) {
        if (!StoneDict.isValidSawType(req.getSawType())) {
            throw new BizException("锯切方式取值非法");
        }
        if (req.getPlanCount() == null || req.getPlanCount() <= 0) {
            throw new BizException("计划板数必须大于 0");
        }
        if (orderRepository.findByOrderNo(req.getOrderNo()) != null) {
            throw new BizException("工单号 " + req.getOrderNo() + " 已存在");
        }
        Block block = blockRepository.findById(req.getBlockId())
                .orElseThrow(() -> new BizException("荒料不存在"));
        if ("ARCHIVED".equals(block.getStatus())) {
            throw new BizException("荒料 " + block.getBlockCode() + " 已结库，不能再开工单");
        }
        CuttingOrder order = new CuttingOrder();
        order.setOrderNo(req.getOrderNo());
        order.setBlockId(block.getId());
        order.setSawType(req.getSawType());
        order.setPlanCount(req.getPlanCount());
        order.setDoneCount(0);
        order.setTeam(req.getTeam());
        order.setStartDate(req.getStartDate());
        order.setStatus("PENDING");
        order.setRemark(req.getRemark());
        orderRepository.save(order);
        return toView(order, blockMap(), sawBatchService.orderStats(order.getId()));
    }

    /** 开工：工单置加工中，荒料置加工中，补开工日期。 */
    @Transactional
    public CuttingOrderView start(Long id) {
        CuttingOrder order = require(id);
        if (!"PENDING".equals(order.getStatus())) {
            throw new BizException("只有待排产的工单才能开工");
        }
        order.setStatus("PROCESSING");
        order.setStartDate(order.getStartDate() == null ? LocalDate.now() : order.getStartDate());
        orderRepository.save(order);
        Block block = blockRepository.findById(order.getBlockId()).orElse(null);
        if (block != null && !"ARCHIVED".equals(block.getStatus())) {
            block.setStatus("PROCESSING");
            blockRepository.save(block);
        }
        return toView(order, blockMap(), sawBatchService.orderStats(order.getId()));
    }

    /** 登记完成板数（累加，不得超过计划板数）。已纳入锯切批次的工单改由批次收口按实产推进。 */
    @Transactional
    public CuttingOrderView progress(Long id, ProgressReq req) {
        CuttingOrder order = require(id);
        if ("SETTLED".equals(order.getStatus())) {
            throw new BizException("已结算的工单不能再登记进度");
        }
        if (order.getSawBaselineCount() != null) {
            throw new BizException("工单已纳入锯切批次交接，完成板数只能由批次收口按合格实产推进，不能手工 +1"
                    + "（逐片扫码登记并核清差异后在批次上收口）");
        }
        int delta = req == null || req.getDelta() == null ? 1 : req.getDelta();
        if (delta <= 0) {
            throw new BizException("本次完成板数必须大于 0");
        }
        int next = order.getDoneCount() + delta;
        if (next > order.getPlanCount()) {
            throw new BizException("累计完成板数 " + next + " 已超过计划板数 " + order.getPlanCount());
        }
        order.setDoneCount(next);
        if (!"PROCESSING".equals(order.getStatus())) {
            order.setStatus("PROCESSING");
        }
        orderRepository.save(order);
        return toView(order, blockMap(), sawBatchService.orderStats(order.getId()));
    }

    /** 完工：按计划板数补齐，工单置已完工，荒料置已完料。已纳入批次的工单不能手工补齐。 */
    @Transactional
    public CuttingOrderView finish(Long id) {
        CuttingOrder order = require(id);
        if ("DONE".equals(order.getStatus()) || "SETTLED".equals(order.getStatus())) {
            throw new BizException("该工单已经完工");
        }
        if (order.getSawBaselineCount() != null) {
            throw new BizException("工单已纳入锯切批次交接，不能手工按计划板数补齐完工；"
                    + "请在各锯切批次核清差异并收口后，由合格实产推进完工");
        }
        order.setDoneCount(order.getPlanCount());
        order.setStatus("DONE");
        order.setFinishDate(order.getFinishDate() == null ? LocalDate.now() : order.getFinishDate());
        if (order.getStartDate() == null) {
            order.setStartDate(order.getFinishDate());
        }
        orderRepository.save(order);
        Block block = blockRepository.findById(order.getBlockId()).orElse(null);
        if (block != null && !"ARCHIVED".equals(block.getStatus())) {
            block.setStatus("DONE");
            blockRepository.save(block);
        }
        return toView(order, blockMap(), sawBatchService.orderStats(order.getId()));
    }

    /** 结算：只有已完工能结算。 */
    @Transactional
    public CuttingOrderView settle(Long id) {
        CuttingOrder order = require(id);
        if (!"DONE".equals(order.getStatus())) {
            throw new BizException("只有已完工的工单才能结算");
        }
        order.setStatus("SETTLED");
        orderRepository.save(order);
        return toView(order, blockMap(), sawBatchService.orderStats(order.getId()));
    }

    private CuttingOrder require(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new BizException("加工工单不存在：id=" + id));
    }

    private Map<Long, Block> blockMap() {
        return blockRepository.listAll().stream()
                .collect(Collectors.toMap(Block::getId, Function.identity(), (a, b) -> a));
    }

    private CuttingOrderView toView(CuttingOrder order, Map<Long, Block> blocks,
                                    SawBatchService.OrderSawStats saw) {
        Block block = blocks.get(order.getBlockId());
        CuttingOrderView view = new CuttingOrderView();
        view.setId(order.getId());
        view.setOrderNo(order.getOrderNo());
        view.setBlockId(order.getBlockId());
        view.setBlockCode(block == null ? "" : block.getBlockCode());
        view.setStoneType(block == null ? "" : block.getStoneType());
        view.setStoneTypeName(block == null ? "" : StoneDict.stoneTypeName(block.getStoneType()));
        view.setSawType(order.getSawType());
        view.setSawTypeName(StoneDict.sawTypeName(order.getSawType()));
        view.setPlanCount(order.getPlanCount());
        view.setDoneCount(order.getDoneCount());
        int plan = order.getPlanCount() == null ? 0 : order.getPlanCount();
        int done = order.getDoneCount() == null ? 0 : order.getDoneCount();
        view.setProgress(plan <= 0 ? 0 : (int) Math.round(done * 100.0 / plan));
        view.setStartDate(order.getStartDate());
        view.setFinishDate(order.getFinishDate());
        view.setTeam(order.getTeam());
        view.setStatus(order.getStatus());
        view.setStatusName(StoneDict.orderStatusName(order.getStatus()));
        view.setRemark(order.getRemark());
        if (saw != null) {
            view.setSawTracked(saw.tracked);
            view.setSawBaselineCount(saw.baseline);
            view.setSawQualifiedCount(saw.qualifiedClosed);
            view.setSawBatchCount(saw.batchCount);
            view.setSawPendingBatchCount(saw.pendingBatchCount);
            view.setSawOpenDiffCount(saw.openDiffCount);
        } else {
            view.setSawTracked(order.getSawBaselineCount() != null);
            view.setSawBaselineCount(order.getSawBaselineCount());
        }
        return view;
    }
}
