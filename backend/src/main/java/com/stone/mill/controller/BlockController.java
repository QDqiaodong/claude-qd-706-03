package com.stone.mill.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stone.mill.common.ApiResponse;
import com.stone.mill.dto.Dtos.BlockSaveReq;
import com.stone.mill.dto.Dtos.StatusReq;
import com.stone.mill.dto.Queries.BlockQuery;
import com.stone.mill.dto.Views.BlockSummary;
import com.stone.mill.dto.Views.BlockView;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.service.BlockService;

import jakarta.validation.Valid;

/** 模块一：荒料台账。列表走 Criteria 动态筛选，底部汇总体积 / 重量 / 采购金额。 */
@RestController
@RequestMapping("/api/blocks")
public class BlockController {

    private final BlockService blockService;

    public BlockController(BlockService blockService) {
        this.blockService = blockService;
    }

    /** 动态条件：stoneType / status / keyword / minVolume / maxVolume / minLength / minWidth / orderBy */
    @GetMapping
    public ApiResponse<PageResult<BlockView>> page(BlockQuery query) {
        return ApiResponse.success(blockService.page(query));
    }

    @GetMapping("/list")
    public ApiResponse<List<BlockView>> list() {
        return ApiResponse.success(blockService.listAll());
    }

    @GetMapping("/summary")
    public ApiResponse<BlockSummary> summary(BlockQuery query) {
        return ApiResponse.success(blockService.summary(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<BlockView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(blockService.detail(id));
    }

    @PostMapping
    public ApiResponse<BlockView> create(@Valid @RequestBody BlockSaveReq req) {
        return ApiResponse.success("荒料已登记", blockService.create(req));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<BlockView> setStatus(@PathVariable("id") Long id,
                                            @RequestBody(required = false) StatusReq req) {
        return ApiResponse.success("荒料状态已更新", blockService.setStatus(id, req == null ? new StatusReq() : req));
    }
}
