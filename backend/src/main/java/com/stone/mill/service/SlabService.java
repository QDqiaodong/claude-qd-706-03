package com.stone.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stone.mill.common.BizException;
import com.stone.mill.dto.Dtos.SlabSaveReq;
import com.stone.mill.dto.Dtos.StatusReq;
import com.stone.mill.dto.Queries.SlabQuery;
import com.stone.mill.dto.Queries.ShippableQuery;
import com.stone.mill.dto.StoneDict;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.dto.Views.SlabSummary;
import com.stone.mill.dto.Views.SlabView;
import com.stone.mill.entity.Block;
import com.stone.mill.entity.QualityHold;
import com.stone.mill.entity.SawOutput;
import com.stone.mill.entity.Slab;
import com.stone.mill.repository.BlockRepository;
import com.stone.mill.repository.SlabRepository;

/**
 * 模块二：大板库存。
 *
 * <p>规则面：
 * <ol>
 *   <li>大板编号唯一；长宽必须为正，面积 = 长×宽/1e4</li>
 *   <li>表面处理与质量等级取值必须在词典内</li>
 *   <li>一张大板必须挂在一块荒料下，且该荒料不能是待排产（先排产才出板）</li>
 *   <li>次品（DEFECT）不能出库：置为已出库 / 已预定前会拦一道</li>
 * </ol>
 */
@Service
public class SlabService {

    private final SlabRepository slabRepository;
    private final BlockRepository blockRepository;
    private final QualityHoldService qualityHoldService;
    private final SawBatchService sawBatchService;

    public SlabService(SlabRepository slabRepository, BlockRepository blockRepository,
                       QualityHoldService qualityHoldService, SawBatchService sawBatchService) {
        this.slabRepository = slabRepository;
        this.blockRepository = blockRepository;
        this.qualityHoldService = qualityHoldService;
        this.sawBatchService = sawBatchService;
    }

    public PageResult<SlabView> page(SlabQuery query) {
        Map<Long, Block> blocks = blockMap();
        Map<Long, QualityHold> holds = qualityHoldService.activeHoldBySlabMap();
        Map<Long, SawOutput> saw = sawBatchService.activeOutputMap(
                slabRepository.listAll().stream().map(Slab::getId).toList());
        List<SlabView> rows = slabRepository.search(query).stream()
                .map(slab -> toView(slab, blocks, holds, saw))
                .collect(Collectors.toList());
        return new PageResult<>(rows, slabRepository.count(query),
                query.getPage(), query.getSize());
    }

    /** 可发货大板：只取在库 / 已预定，供发货页挑选（隔离冻结已在仓储层排除）。 */
    public PageResult<SlabView> shippable(ShippableQuery query) {
        Map<Long, Block> blocks = blockMap();
        List<SlabView> rows = slabRepository.searchShippable(query).stream()
                .map(slab -> toView(slab, blocks, java.util.Map.of(), java.util.Map.of()))
                .collect(Collectors.toList());
        return new PageResult<>(rows, slabRepository.countShippable(query),
                query.getPage(), query.getSize());
    }

    public SlabSummary summary(SlabQuery query) {
        return slabRepository.summarize(query);
    }

    public List<SlabView> listByBlock(Long blockId) {
        Map<Long, Block> blocks = blockMap();
        Map<Long, QualityHold> holds = qualityHoldService.activeHoldBySlabMap();
        Map<Long, SawOutput> saw = sawBatchService.activeOutputMap(
                slabRepository.listByBlock(blockId).stream().map(Slab::getId).toList());
        return slabRepository.listByBlock(blockId).stream()
                .map(slab -> toView(slab, blocks, holds, saw))
                .collect(Collectors.toList());
    }

    public SlabView detail(Long id) {
        Slab slab = slabRepository.findById(id).orElseThrow(() -> new BizException("大板不存在：id=" + id));
        return toView(slab, blockMap(), qualityHoldService.activeHoldBySlabMap(),
                sawBatchService.activeOutputMap(List.of(slab.getId())));
    }

    @Transactional
    public SlabView create(SlabSaveReq req) {
        if (!StoneDict.isValidFinish(req.getFinish())) {
            throw new BizException("表面处理取值非法");
        }
        if (!StoneDict.isValidGrade(req.getGrade())) {
            throw new BizException("质量等级取值非法，应为 A / B / C / 次品");
        }
        if (req.getLengthCm() == null || req.getLengthCm() <= 0 || req.getWidthCm() == null || req.getWidthCm() <= 0) {
            throw new BizException("大板长、宽都必须大于 0");
        }
        Block block = blockRepository.findById(req.getBlockId())
                .orElseThrow(() -> new BizException("所属荒料不存在"));
        if ("PENDING".equals(block.getStatus())) {
            throw new BizException("荒料 " + block.getBlockCode() + " 仍是待排产，先排产再登记大板");
        }
        if (slabRepository.findByCode(req.getSlabCode()) != null) {
            throw new BizException("大板编号 " + req.getSlabCode() + " 已存在");
        }
        Slab slab = new Slab();
        slab.setSlabCode(req.getSlabCode());
        slab.setBlockId(block.getId());
        slab.setLengthCm(req.getLengthCm());
        slab.setWidthCm(req.getWidthCm());
        slab.setThicknessCm(req.getThicknessCm() == null ? BigDecimal.valueOf(2.0) : req.getThicknessCm());
        slab.setFinish(req.getFinish());
        slab.setGrade(req.getGrade());
        slab.setLocation(req.getLocation());
        slab.setStatus("INSTOCK");
        slab.setRemark(req.getRemark());
        slab.setAreaM2(BigDecimal.valueOf(req.getLengthCm())
                .multiply(BigDecimal.valueOf(req.getWidthCm()))
                .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP));
        slabRepository.save(slab);
        return toView(slab, blockMap(), qualityHoldService.activeHoldBySlabMap(),
                sawBatchService.activeOutputMap(List.of(slab.getId())));
    }

    @Transactional
    public SlabView setStatus(Long id, StatusReq req) {
        if (!StoneDict.isValidSlabStatus(req.getStatus())) {
            throw new BizException("大板状态取值非法");
        }
        Slab slab = slabRepository.findById(id).orElseThrow(() -> new BizException("大板不存在：id=" + id));
        if ("SCRAP".equals(slab.getStatus())) {
            throw new BizException("已报废的大板不能再流转");
        }
        if (("RESERVED".equals(req.getStatus()) || "OUTBOUND".equals(req.getStatus()))
                && "DEFECT".equals(slab.getGrade())) {
            throw new BizException("次品大板不允许预定或出库");
        }
        // 隔离中 / 待复检期间冻结发货类流转（预定 / 出库），防止绕过质量复检
        if (qualityHoldService.activeHoldBySlabMap().containsKey(id)
                && ("RESERVED".equals(req.getStatus()) || "OUTBOUND".equals(req.getStatus()))) {
            throw new BizException("大板正处于质量隔离 / 待复检，发货已冻结，请在质量处置页复检放行或报废后再操作");
        }
        slab.setStatus(req.getStatus());
        if (req.getRemark() != null) {
            slab.setRemark(req.getRemark());
        }
        slabRepository.save(slab);
        return toView(slab, blockMap(), qualityHoldService.activeHoldBySlabMap(),
                sawBatchService.activeOutputMap(List.of(id)));
    }

    private Map<Long, Block> blockMap() {
        return blockRepository.listAll().stream()
                .collect(Collectors.toMap(Block::getId, Function.identity(), (a, b) -> a));
    }

    private SlabView toView(Slab slab, Map<Long, Block> blocks, Map<Long, QualityHold> holds,
                            Map<Long, SawOutput> sawOutputs) {
        Block block = blocks.get(slab.getBlockId());
        SlabView view = new SlabView();
        view.setId(slab.getId());
        view.setSlabCode(slab.getSlabCode());
        view.setBlockId(slab.getBlockId());
        view.setBlockCode(block == null ? "" : block.getBlockCode());
        view.setStoneType(block == null ? "" : block.getStoneType());
        view.setStoneTypeName(block == null ? "" : StoneDict.stoneTypeName(block.getStoneType()));
        view.setLengthCm(slab.getLengthCm());
        view.setWidthCm(slab.getWidthCm());
        view.setThicknessCm(slab.getThicknessCm());
        view.setAreaM2(slab.getAreaM2());
        view.setFinish(slab.getFinish());
        view.setFinishName(StoneDict.finishName(slab.getFinish()));
        view.setGrade(slab.getGrade());
        view.setGradeName(StoneDict.gradeName(slab.getGrade()));
        view.setLocation(slab.getLocation());
        view.setStatus(slab.getStatus());
        view.setStatusName(StoneDict.slabStatusName(slab.getStatus()));
        view.setRemark(slab.getRemark());
        QualityHold hold = holds.get(slab.getId());
        if (hold != null) {
            view.setQualityHold(true);
            view.setQualityHoldNo(hold.getHoldNo());
            view.setQualityHoldStatus(hold.getStatus());
            view.setQualityHoldStatusName(StoneDict.holdStatusName(hold.getStatus()));
            view.setQualityDefectName(StoneDict.defectTypeName(hold.getDefectType()));
        }
        SawOutput output = sawOutputs == null ? null : sawOutputs.get(slab.getId());
        if (output != null) {
            view.setSawTracked(true);
            com.stone.mill.entity.SawBatch sb = sawBatchService.batchForView(output.getBatchId());
            view.setSawBatchNo(sb == null ? null : sb.getBatchNo());
            view.setSawBatchStatusName(sb == null ? null : StoneDict.sawBatchStatusName(sb.getStatus()));
        }
        return view;
    }
}
