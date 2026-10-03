package com.stone.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stone.mill.common.BizException;
import com.stone.mill.dto.Dtos.RelayoutReq;
import com.stone.mill.dto.StoneDict;
import com.stone.mill.dto.Views.LayoutItemView;
import com.stone.mill.dto.Views.LayoutPlan;
import com.stone.mill.entity.Block;
import com.stone.mill.entity.LayoutItem;
import com.stone.mill.entity.Slab;
import com.stone.mill.repository.BlockRepository;
import com.stone.mill.repository.LayoutRepository;
import com.stone.mill.repository.SlabRepository;

/**
 * 模块四：排板方案。
 *
 * <p>首页那张 SVG 切割排版图的数据来源：一块荒料的排板面（长 × 宽）上，
 * 用货架式（shelf）算法把已切出的大板矩形从原点开始、从左到右、放不下就换行地铺开，
 * 铺完算损耗率 =（排板面 - 已排板材面积）/ 排板面。
 */
@Service
public class LayoutService {

    private final LayoutRepository layoutRepository;
    private final SlabRepository slabRepository;
    private final BlockRepository blockRepository;

    public LayoutService(LayoutRepository layoutRepository, SlabRepository slabRepository,
                         BlockRepository blockRepository) {
        this.layoutRepository = layoutRepository;
        this.slabRepository = slabRepository;
        this.blockRepository = blockRepository;
    }

    /** 荒料当前的排板方案；还没有排过就先按现有大板排一次再返回。 */
    @Transactional
    public LayoutPlan plan(Long blockId) {
        Block block = requireBlock(blockId);
        List<LayoutItem> items = layoutRepository.listByBlock(blockId);
        if (items.isEmpty() && !slabRepository.listByBlock(blockId).isEmpty()) {
            relayout(blockId, null);
            items = layoutRepository.listByBlock(blockId);
        }
        return build(block, items);
    }

    /** 重新排板：清掉旧排布，按剩余大板重算坐标与损耗率。 */
    @Transactional
    public LayoutPlan relayout(Long blockId, RelayoutReq req) {
        Block block = requireBlock(blockId);
        List<Slab> slabs = slabRepository.listByBlock(blockId);
        if (req != null && req.getSlabIds() != null && !req.getSlabIds().isEmpty()) {
            slabs = slabs.stream().filter(s -> req.getSlabIds().contains(s.getId())).collect(Collectors.toList());
        }
        if (slabs.isEmpty()) {
            throw new BizException("该荒料下还没有大板，无法排板");
        }

        // 排板面：沿荒料长度方向为 x，沿宽度方向为 y
        int faceLength = block.getLengthCm();
        int faceWidth = block.getWidthCm();
        List<Slab> ordered = new ArrayList<>(slabs);
        ordered.sort(Comparator.comparing(Slab::getWidthCm, Comparator.reverseOrder())
                .thenComparing(Slab::getLengthCm, Comparator.reverseOrder())
                .thenComparing(Slab::getSlabCode));

        BigDecimal faceArea = area(faceLength, faceWidth);
        BigDecimal placedArea = BigDecimal.ZERO;

        layoutRepository.deleteByBlock(blockId);
        int seq = 0;
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (Slab slab : ordered) {
            int w = slab.getLengthCm();
            int h = slab.getWidthCm();
            if (x + w > faceLength) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            if (y + h > faceWidth) {
                continue; // 剩余高度放不下，留作虚线留白
            }
            seq++;
            placedArea = placedArea.add(area(w, h));
            LayoutItem item = new LayoutItem();
            item.setBlockId(blockId);
            item.setSlabId(slab.getId());
            item.setXPos(x);
            item.setYPos(y);
            item.setItemWidth(w);
            item.setItemHeight(h);
            item.setSeqNo(seq);
            layoutRepository.save(item);
            x += w;
            rowHeight = Math.max(rowHeight, h);
        }

        BigDecimal wasteRate = faceArea.compareTo(BigDecimal.ZERO) <= 0
                ? BigDecimal.ZERO
                : BigDecimal.ONE.subtract(placedArea.divide(faceArea, 6, RoundingMode.HALF_UP))
                        .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

        List<LayoutItem> saved = layoutRepository.listByBlock(blockId);
        for (LayoutItem item : saved) {
            item.setWasteRate(wasteRate);
            layoutRepository.save(item);
        }
        return build(block, saved);
    }

    private LayoutPlan build(Block block, List<LayoutItem> items) {
        Map<Long, Slab> slabs = slabRepository.listByBlock(block.getId()).stream()
                .collect(Collectors.toMap(Slab::getId, Function.identity(), (a, b) -> a));
        BigDecimal faceArea = area(block.getLengthCm(), block.getWidthCm());
        BigDecimal placedArea = BigDecimal.ZERO;
        BigDecimal wasteRate = BigDecimal.ZERO;

        List<LayoutItemView> views = new ArrayList<>();
        for (LayoutItem item : items) {
            Slab slab = slabs.get(item.getSlabId());
            placedArea = placedArea.add(area(item.getItemWidth(), item.getItemHeight()));
            if (item.getWasteRate() != null) {
                wasteRate = item.getWasteRate();
            }
            LayoutItemView view = new LayoutItemView();
            view.setId(item.getId());
            view.setSlabId(item.getSlabId());
            view.setSlabCode(slab == null ? "" : slab.getSlabCode());
            view.setXPos(item.getXPos());
            view.setYPos(item.getYPos());
            view.setWidth(item.getItemWidth());
            view.setHeight(item.getItemHeight());
            view.setSeqNo(item.getSeqNo());
            view.setGrade(slab == null ? "" : slab.getGrade());
            view.setGradeName(slab == null ? "" : StoneDict.gradeName(slab.getGrade()));
            view.setFinishName(slab == null ? "" : StoneDict.finishName(slab.getFinish()));
            view.setAreaM2(slab == null ? null : slab.getAreaM2());
            view.setStatus(slab == null ? "" : slab.getStatus());
            view.setStatusName(slab == null ? "" : StoneDict.slabStatusName(slab.getStatus()));
            view.setColor(StoneDict.gradeColor(slab == null ? "" : slab.getGrade()));
            views.add(view);
        }

        LayoutPlan plan = new LayoutPlan();
        plan.setBlockId(block.getId());
        plan.setBlockCode(block.getBlockCode());
        plan.setStoneType(block.getStoneType());
        plan.setStoneTypeName(StoneDict.stoneTypeName(block.getStoneType()));
        plan.setLengthCm(block.getLengthCm());
        plan.setWidthCm(block.getWidthCm());
        plan.setFaceArea(faceArea);
        plan.setPlacedArea(placedArea);
        plan.setRemainArea(faceArea.subtract(placedArea).setScale(2, RoundingMode.HALF_UP));
        plan.setWasteRate(wasteRate);
        plan.setPlacedCount(views.size());
        plan.setSlabCount(slabs.size());
        plan.setItems(views);
        return plan;
    }

    private Block requireBlock(Long blockId) {
        return blockRepository.findById(blockId).orElseThrow(() -> new BizException("荒料不存在：id=" + blockId));
    }

    private BigDecimal area(int lengthCm, int widthCm) {
        return BigDecimal.valueOf(lengthCm).multiply(BigDecimal.valueOf(widthCm))
                .divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP);
    }
}
