package com.stone.mill.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stone.mill.common.ApiResponse;
import com.stone.mill.dto.Dtos.QualityHoldCreateReq;
import com.stone.mill.dto.Dtos.RecheckReq;
import com.stone.mill.dto.Dtos.SubmitRecheckReq;
import com.stone.mill.dto.Queries.QualityHoldQuery;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.dto.Views.QualityHoldSummary;
import com.stone.mill.dto.Views.QualityHoldView;
import com.stone.mill.service.QualityHoldService;

import jakarta.validation.Valid;

/**
 * 模块六/七：大板质量隔离与复检放行。
 *
 * <p>独立质量处置页：按状态 / 缺陷类型 / 严重程度 / 荒料编号 / 大板编号筛选，
 * 列表带当前阻断的发货单，详情是建立 → 送复检 → 放行 / 报废的时间线。
 */
@RestController
@RequestMapping("/api/quality-holds")
public class QualityHoldController {

    private final QualityHoldService qualityHoldService;

    public QualityHoldController(QualityHoldService qualityHoldService) {
        this.qualityHoldService = qualityHoldService;
    }

    @GetMapping
    public ApiResponse<PageResult<QualityHoldView>> page(QualityHoldQuery query) {
        return ApiResponse.success(qualityHoldService.page(query));
    }

    @GetMapping("/summary")
    public ApiResponse<QualityHoldSummary> summary() {
        return ApiResponse.success(qualityHoldService.summary());
    }

    @GetMapping("/{id}")
    public ApiResponse<QualityHoldView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(qualityHoldService.detail(id));
    }

    /** 大板当前未收口隔离（无则 data 为 null），大板详情 / 发货页用。 */
    @GetMapping("/active-by-slab")
    public ApiResponse<QualityHoldView> activeBySlab(@RequestParam("slabId") Long slabId) {
        return ApiResponse.success(qualityHoldService.activeBySlab(slabId));
    }

    /** 建立隔离（并发重复发起只有一条成功，失败方 409 并拿到当前记录）。 */
    @PostMapping
    public ApiResponse<QualityHoldView> create(@Valid @RequestBody QualityHoldCreateReq req) {
        return ApiResponse.success("质量隔离已建立，大板发货冻结", qualityHoldService.create(req));
    }

    /** 送复检：隔离中 → 待复检。 */
    @PostMapping("/{id}/submit-recheck")
    public ApiResponse<QualityHoldView> submitRecheck(@PathVariable("id") Long id,
                                                      @RequestBody(required = false) SubmitRecheckReq req) {
        return ApiResponse.success("已送复检", qualityHoldService.submitRecheck(
                id, req == null ? new SubmitRecheckReq() : req));
    }

    /**
     * 复检结论（追加留痕）：PASS 放行 / REWORK 返工观察（仅追加意见）/ SCRAP 报废。
     */
    @PostMapping("/{id}/recheck")
    public ApiResponse<QualityHoldView> recheck(@PathVariable("id") Long id,
                                                @Valid @RequestBody RecheckReq req) {
        String msg = switch (req.getConclusion()) {
            case "PASS" -> "复检合格，已放行并解除发货挂起";
            case "REWORK" -> "已登记复检意见（返工观察），记录仍在待复检";
            case "SCRAP" -> "已判定报废：大板收口，未发运发货单明细已移除并重算金额";
            default -> "复检结论已登记";
        };
        return ApiResponse.success(msg, qualityHoldService.recheck(id, req));
    }

    /** 隔离中直接判废（不走待复检，仍补留痕），用于现场明确不可修复的情况。 */
    @PostMapping("/{id}/scrap")
    public ApiResponse<QualityHoldView> scrap(@PathVariable("id") Long id,
                                              @RequestBody(required = false) RecheckReq req) {
        return ApiResponse.success("已判定报废并收口", qualityHoldService.scrap(
                id, req == null ? new RecheckReq() : req));
    }
}
