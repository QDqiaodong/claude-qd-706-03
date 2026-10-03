package com.stone.mill.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stone.mill.common.ApiResponse;
import com.stone.mill.dto.Dtos.SlabSaveReq;
import com.stone.mill.dto.Dtos.StatusReq;
import com.stone.mill.dto.Queries.ShippableQuery;
import com.stone.mill.dto.Queries.SlabQuery;
import com.stone.mill.dto.StoneDict;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.dto.Views.SlabSummary;
import com.stone.mill.dto.Views.SlabView;
import com.stone.mill.service.SlabService;

import jakarta.validation.Valid;

/** 模块二：大板库存。库存页卡片墙与发货页「可发货大板」共用同一套 Criteria 筛选。 */
@RestController
@RequestMapping("/api/slabs")
public class SlabController {

    private final SlabService slabService;

    public SlabController(SlabService slabService) {
        this.slabService = slabService;
    }

    /** 动态条件：stoneType / grade / status / finish / minArea / maxArea / minLength / minWidth / keyword */
    @GetMapping
    public ApiResponse<PageResult<SlabView>> page(SlabQuery query) {
        return ApiResponse.success(slabService.page(query));
    }

    /** 可发货大板：默认只出在库 + 已预定。 */
    @GetMapping("/shippable")
    public ApiResponse<PageResult<SlabView>> shippable(ShippableQuery query) {
        if (query.getStatuses() == null || query.getStatuses().isEmpty()) {
            query.setStatuses(StoneDict.SHIPPABLE_STATUS);
        }
        return ApiResponse.success(slabService.shippable(query));
    }

    @GetMapping("/summary")
    public ApiResponse<SlabSummary> summary(SlabQuery query) {
        return ApiResponse.success(slabService.summary(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<SlabView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(slabService.detail(id));
    }

    @GetMapping("/by-block")
    public ApiResponse<List<SlabView>> byBlock(@RequestParam("blockId") Long blockId) {
        return ApiResponse.success(slabService.listByBlock(blockId));
    }

    @PostMapping
    public ApiResponse<SlabView> create(@Valid @RequestBody SlabSaveReq req) {
        return ApiResponse.success("大板已登记", slabService.create(req));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<SlabView> setStatus(@PathVariable("id") Long id,
                                           @RequestBody(required = false) StatusReq req) {
        return ApiResponse.success("大板状态已更新", slabService.setStatus(id, req == null ? new StatusReq() : req));
    }
}
