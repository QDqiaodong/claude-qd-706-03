package com.stone.mill.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stone.mill.common.ApiResponse;
import com.stone.mill.dto.Dtos.OrderSaveReq;
import com.stone.mill.dto.Dtos.ProgressReq;
import com.stone.mill.dto.Queries.OrderQuery;
import com.stone.mill.dto.Views.CuttingOrderView;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.service.CuttingOrderService;

import jakarta.validation.Valid;

/** 模块三：加工工单。表格 + 进度条（已完成 / 计划板数）。 */
@RestController
@RequestMapping("/api/cutting-orders")
public class CuttingOrderController {

    private final CuttingOrderService orderService;

    public CuttingOrderController(CuttingOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ApiResponse<PageResult<CuttingOrderView>> page(OrderQuery query) {
        return ApiResponse.success(orderService.page(query));
    }

    @GetMapping("/list")
    public ApiResponse<List<CuttingOrderView>> list() {
        return ApiResponse.success(orderService.listAll());
    }

    @GetMapping("/{id}")
    public ApiResponse<CuttingOrderView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(orderService.detail(id));
    }

    @PostMapping
    public ApiResponse<CuttingOrderView> create(@Valid @RequestBody OrderSaveReq req) {
        return ApiResponse.success("加工工单已创建", orderService.create(req));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<CuttingOrderView> start(@PathVariable("id") Long id) {
        return ApiResponse.success("工单已开工", orderService.start(id));
    }

    @PostMapping("/{id}/progress")
    public ApiResponse<CuttingOrderView> progress(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) ProgressReq req) {
        return ApiResponse.success("完成板数已登记",
                orderService.progress(id, req == null ? new ProgressReq() : req));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<CuttingOrderView> finish(@PathVariable("id") Long id) {
        return ApiResponse.success("工单已完工", orderService.finish(id));
    }

    @PostMapping("/{id}/settle")
    public ApiResponse<CuttingOrderView> settle(@PathVariable("id") Long id) {
        return ApiResponse.success("工单已结算", orderService.settle(id));
    }
}
