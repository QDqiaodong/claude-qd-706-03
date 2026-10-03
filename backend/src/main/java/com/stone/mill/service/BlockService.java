package com.stone.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stone.mill.common.BizException;
import com.stone.mill.dto.Dtos.BlockSaveReq;
import com.stone.mill.dto.Dtos.StatusReq;
import com.stone.mill.dto.Queries.BlockQuery;
import com.stone.mill.dto.StoneDict;
import com.stone.mill.dto.Views.BlockSummary;
import com.stone.mill.dto.Views.BlockView;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.entity.Block;
import com.stone.mill.repository.BlockRepository;
import com.stone.mill.repository.SlabRepository;

/**
 * 模块一：荒料。
 *
 * <p>规则面：
 * <ol>
 *   <li>荒料编号唯一；长宽高必须为正</li>
 *   <li>体积 = 长×宽×高/1e6（m³），重量 = 体积 × 石种密度（吨），入库时算好落库</li>
 *   <li>石种与状态取值必须在词典内</li>
 *   <li>结库（ARCHIVED）的荒料不允许再改回加工中</li>
 * </ol>
 */
@Service
public class BlockService {

    private final BlockRepository blockRepository;
    private final SlabRepository slabRepository;

    public BlockService(BlockRepository blockRepository, SlabRepository slabRepository) {
        this.blockRepository = blockRepository;
        this.slabRepository = slabRepository;
    }

    public PageResult<BlockView> page(BlockQuery query) {
        List<BlockView> rows = blockRepository.search(query).stream().map(this::toView).collect(Collectors.toList());
        return new PageResult<>(rows, blockRepository.count(query),
                query.getPage(), query.getSize());
    }

    public BlockSummary summary(BlockQuery query) {
        return blockRepository.summarize(query);
    }

    public List<BlockView> listAll() {
        return blockRepository.listAll().stream().map(this::toView).collect(Collectors.toList());
    }

    public BlockView detail(Long id) {
        return toView(require(id));
    }

    @Transactional
    public BlockView create(BlockSaveReq req) {
        if (!StoneDict.isValidStoneType(req.getStoneType())) {
            throw new BizException("石种取值非法，应为 大理石/花岗岩/玉石/石英石/洞石 之一");
        }
        if (req.getLengthCm() == null || req.getLengthCm() <= 0
                || req.getWidthCm() == null || req.getWidthCm() <= 0
                || req.getHeightCm() == null || req.getHeightCm() <= 0) {
            throw new BizException("荒料长、宽、高都必须大于 0");
        }
        if (blockRepository.findByCode(req.getBlockCode()) != null) {
            throw new BizException("荒料编号 " + req.getBlockCode() + " 已存在");
        }
        Block block = new Block();
        block.setBlockCode(req.getBlockCode());
        block.setQuarry(req.getQuarry());
        block.setStoneType(req.getStoneType());
        block.setLengthCm(req.getLengthCm());
        block.setWidthCm(req.getWidthCm());
        block.setHeightCm(req.getHeightCm());
        block.setInboundDate(req.getInboundDate() == null ? java.time.LocalDate.now() : req.getInboundDate());
        block.setPurchasePrice(req.getPurchasePrice());
        block.setSupplier(req.getSupplier());
        block.setStatus("PENDING");
        block.setRemark(req.getRemark());
        recalc(block);
        blockRepository.save(block);
        return toView(block);
    }

    @Transactional
    public BlockView setStatus(Long id, StatusReq req) {
        if (!StoneDict.isValidBlockStatus(req.getStatus())) {
            throw new BizException("荒料状态取值非法");
        }
        Block block = require(id);
        if ("ARCHIVED".equals(block.getStatus()) && !"ARCHIVED".equals(req.getStatus())) {
            throw new BizException("已结库的荒料不能再流转到其它状态");
        }
        block.setStatus(req.getStatus());
        if (req.getRemark() != null) {
            block.setRemark(req.getRemark());
        }
        blockRepository.save(block);
        return toView(block);
    }

    /** 体积与重量按石种密度反算，保证与台账口径一致。 */
    private void recalc(Block block) {
        BigDecimal volume = BigDecimal.valueOf(block.getLengthCm())
                .multiply(BigDecimal.valueOf(block.getWidthCm()))
                .multiply(BigDecimal.valueOf(block.getHeightCm()))
                .divide(BigDecimal.valueOf(1000000), 2, RoundingMode.HALF_UP);
        BigDecimal weight = volume.multiply(StoneDict.stoneDensity(block.getStoneType()))
                .setScale(2, RoundingMode.HALF_UP);
        block.setVolumeM3(volume);
        block.setWeightTon(weight);
    }

    private Block require(Long id) {
        return blockRepository.findById(id).orElseThrow(() -> new BizException("荒料不存在：id=" + id));
    }

    private BlockView toView(Block block) {
        BlockView view = new BlockView();
        view.setId(block.getId());
        view.setBlockCode(block.getBlockCode());
        view.setQuarry(block.getQuarry());
        view.setStoneType(block.getStoneType());
        view.setStoneTypeName(StoneDict.stoneTypeName(block.getStoneType()));
        view.setLengthCm(block.getLengthCm());
        view.setWidthCm(block.getWidthCm());
        view.setHeightCm(block.getHeightCm());
        view.setVolumeM3(block.getVolumeM3());
        view.setWeightTon(block.getWeightTon());
        view.setInboundDate(block.getInboundDate());
        view.setPurchasePrice(block.getPurchasePrice());
        view.setPurchaseAmount(block.getPurchasePrice() == null || block.getVolumeM3() == null
                ? BigDecimal.ZERO
                : block.getVolumeM3().multiply(block.getPurchasePrice()).setScale(2, RoundingMode.HALF_UP));
        view.setSupplier(block.getSupplier());
        view.setStatus(block.getStatus());
        view.setStatusName(StoneDict.blockStatusName(block.getStatus()));
        view.setRemark(block.getRemark());
        view.setSlabCount(slabRepository.countByBlock(block.getId()));
        view.setSlabArea(slabRepository.sumAreaByBlock(block.getId()));
        return view;
    }
}
