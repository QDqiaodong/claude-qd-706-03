package com.stone.mill.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stone.mill.common.ApiResponse;
import com.stone.mill.dto.Dtos.DeliveryItemReq;
import com.stone.mill.dto.Dtos.DeliverySaveReq;
import com.stone.mill.dto.Dtos.StatusReq;
import com.stone.mill.dto.Queries.DeliveryQuery;
import com.stone.mill.dto.Views.DeliveryItemView;
import com.stone.mill.dto.Views.DeliverySummary;
import com.stone.mill.dto.Views.DeliveryView;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.service.DeliveryService;

import jakarta.validation.Valid;

/** 模块五：出库发货。表格 + 金额汇总 + 展开行看大板明细。 */
@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {

    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @GetMapping
    public ApiResponse<PageResult<DeliveryView>> page(DeliveryQuery query) {
        return ApiResponse.success(deliveryService.page(query));
    }

    @GetMapping("/summary")
    public ApiResponse<DeliverySummary> summary(DeliveryQuery query) {
        return ApiResponse.success(deliveryService.summary(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<DeliveryView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(deliveryService.detail(id));
    }

    @GetMapping("/{id}/items")
    public ApiResponse<List<DeliveryItemView>> items(@PathVariable("id") Long id) {
        return ApiResponse.success(deliveryService.items(id));
    }

    @PostMapping
    public ApiResponse<DeliveryView> create(@Valid @RequestBody DeliverySaveReq req) {
        return ApiResponse.success("发货单已创建", deliveryService.create(req));
    }

    @PostMapping("/{id}/items")
    public ApiResponse<DeliveryItemView> addItem(@PathVariable("id") Long id,
                                                 @Valid @RequestBody DeliveryItemReq req) {
        return ApiResponse.success("大板已加入发货单", deliveryService.addItem(id, req));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<DeliveryView> setStatus(@PathVariable("id") Long id,
                                               @RequestBody(required = false) StatusReq req) {
        return ApiResponse.success("发货状态已更新",
                deliveryService.setStatus(id, req == null ? new StatusReq() : req));
    }
}
