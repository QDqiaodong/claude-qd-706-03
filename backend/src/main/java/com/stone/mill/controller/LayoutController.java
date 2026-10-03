package com.stone.mill.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stone.mill.common.ApiResponse;
import com.stone.mill.dto.Dtos.RelayoutReq;
import com.stone.mill.dto.Views.LayoutPlan;
import com.stone.mill.service.LayoutService;

/** 模块四：排板方案 —— 首页 SVG 切割排版图的数据源。 */
@RestController
@RequestMapping("/api/layouts")
public class LayoutController {

    private final LayoutService layoutService;

    public LayoutController(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    /** 某块荒料当前的排板方案（含损耗率与已排 / 剩余面积）。 */
    @GetMapping
    public ApiResponse<LayoutPlan> plan(@RequestParam("blockId") Long blockId) {
        return ApiResponse.success(layoutService.plan(blockId));
    }

    /** 重新排板：按剩余大板重算 x/y/宽/高 与损耗率。 */
    @PostMapping("/{blockId}/relayout")
    public ApiResponse<LayoutPlan> relayout(@PathVariable("blockId") Long blockId,
                                            @RequestBody(required = false) RelayoutReq req) {
        return ApiResponse.success("已按剩余荒料重算排布", layoutService.relayout(blockId, req));
    }
}
