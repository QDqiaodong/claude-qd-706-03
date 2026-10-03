package com.stone.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.stone.mill.dto.Queries.BlockQuery;
import com.stone.mill.dto.Queries.DeliveryQuery;
import com.stone.mill.dto.Queries.SlabQuery;
import com.stone.mill.dto.StoneDict;
import com.stone.mill.dto.Views.BlockSummary;
import com.stone.mill.dto.Views.DeliverySummary;
import com.stone.mill.dto.Views.Overview;
import com.stone.mill.dto.Views.SlabSummary;
import com.stone.mill.dto.Views.TreeNode;
import com.stone.mill.entity.Block;
import com.stone.mill.entity.Slab;
import com.stone.mill.repository.BlockRepository;
import com.stone.mill.repository.CuttingOrderRepository;
import com.stone.mill.repository.DeliveryRepository;
import com.stone.mill.repository.LayoutRepository;
import com.stone.mill.repository.SlabRepository;

/** 首页总览计数器与左侧「荒料批次 → 大板」树。 */
@Service
public class MillService {

    private final BlockRepository blockRepository;
    private final SlabRepository slabRepository;
    private final CuttingOrderRepository orderRepository;
    private final LayoutRepository layoutRepository;
    private final DeliveryRepository deliveryRepository;

    public MillService(BlockRepository blockRepository, SlabRepository slabRepository,
                       CuttingOrderRepository orderRepository, LayoutRepository layoutRepository,
                       DeliveryRepository deliveryRepository) {
        this.blockRepository = blockRepository;
        this.slabRepository = slabRepository;
        this.orderRepository = orderRepository;
        this.layoutRepository = layoutRepository;
        this.deliveryRepository = deliveryRepository;
    }

    public Overview overview() {
        BlockQuery blockQuery = new BlockQuery();
        SlabQuery slabQuery = new SlabQuery();
        DeliveryQuery deliveryQuery = new DeliveryQuery();

        BlockSummary blocks = blockRepository.summarize(blockQuery);
        SlabSummary slabs = slabRepository.summarize(slabQuery);
        DeliverySummary deliveries = deliveryRepository.summarize(deliveryQuery);

        Overview view = new Overview();
        view.setBlockCount(blocks.getBlockCount());
        view.setBlockVolume(blocks.getTotalVolume());
        view.setBlockWeight(blocks.getTotalWeight());
        view.setProcessingBlock(blocks.getProcessingCount());
        view.setPendingBlock(blocks.getPendingCount());
        view.setSlabCount(slabs.getSlabCount());
        view.setSlabArea(slabs.getTotalArea());
        view.setInstockArea(slabs.getInstockArea());
        view.setInstockSlab(slabs.getInstockCount());
        view.setReservedSlab(slabs.getReservedCount());
        view.setOrderProcessing(orderRepository.countByStatus("PROCESSING"));
        view.setOrderPending(orderRepository.countByStatus("PENDING"));
        view.setAvgWasteRate(layoutRepository.avgWasteRate().setScale(2, RoundingMode.HALF_UP));
        view.setDeliveryCount(deliveries.getDeliveryCount());
        view.setDeliveryAmount(deliveries.getTotalAmount());
        return view;
    }

    /** 左侧树：荒料批次为一级，其下大板为二级；三个顶部筛选都会作用到树上。 */
    public List<TreeNode> tree(String stoneType, String grade, String status) {
        List<Block> blocks = blockRepository.listAll();
        List<Slab> slabs = slabRepository.listAll();
        List<TreeNode> roots = new ArrayList<>();
        for (Block block : blocks) {
            if (notBlank(stoneType) && !stoneType.equals(block.getStoneType())) {
                continue;
            }
            TreeNode node = new TreeNode();
            node.setKey("block-" + block.getId());
            node.setType("block");
            node.setId(block.getId());
            node.setBlockId(block.getId());
            node.setStoneType(block.getStoneType());
            node.setAreaM2(block.getVolumeM3());
            node.setLabel(block.getBlockCode() + " · " + StoneDict.stoneTypeName(block.getStoneType())
                    + " · " + (block.getVolumeM3() == null ? "0" : block.getVolumeM3()) + "m³");

            List<TreeNode> children = new ArrayList<>();
            for (Slab slab : slabs) {
                if (slab.getBlockId() == null || !slab.getBlockId().equals(block.getId())) {
                    continue;
                }
                if (notBlank(grade) && !grade.equals(slab.getGrade())) {
                    continue;
                }
                if (notBlank(status) && !status.equals(slab.getStatus())) {
                    continue;
                }
                TreeNode leaf = new TreeNode();
                leaf.setKey("slab-" + slab.getId());
                leaf.setType("slab");
                leaf.setId(slab.getId());
                leaf.setBlockId(block.getId());
                leaf.setGrade(slab.getGrade());
                leaf.setAreaM2(slab.getAreaM2());
                leaf.setLabel(slab.getSlabCode() + " · " + StoneDict.gradeName(slab.getGrade())
                        + " · " + (slab.getAreaM2() == null ? BigDecimal.ZERO : slab.getAreaM2()) + "m²");
                children.add(leaf);
            }
            node.setChildren(children);
            roots.add(node);
        }
        return roots;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
