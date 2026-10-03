package com.stone.mill.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stone.mill.common.ApiResponse;
import com.stone.mill.dto.Views.Overview;
import com.stone.mill.dto.Views.TreeNode;
import com.stone.mill.service.MillService;

/** 首页总览计数器与左侧荒料树。 */
@RestController
@RequestMapping("/api/mill")
public class MillController {

    private final MillService millService;

    public MillController(MillService millService) {
        this.millService = millService;
    }

    @GetMapping("/overview")
    public ApiResponse<Overview> overview() {
        return ApiResponse.success(millService.overview());
    }

    /** 左侧树：荒料批次 → 大板，受顶部三个筛选影响。 */
    @GetMapping("/tree")
    public ApiResponse<List<TreeNode>> tree(@RequestParam(value = "stoneType", required = false) String stoneType,
                                            @RequestParam(value = "grade", required = false) String grade,
                                            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.success(millService.tree(stoneType, grade, status));
    }
}
