package com.stone.mill.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stone.mill.common.ApiResponse;
import com.stone.mill.dto.Dtos.SawBatchCreateReq;
import com.stone.mill.dto.Dtos.SawCloseReq;
import com.stone.mill.dto.Dtos.SawDiffResolveReq;
import com.stone.mill.dto.Dtos.SawHandoverReq;
import com.stone.mill.dto.Dtos.SawOutputRegisterReq;
import com.stone.mill.dto.Queries.SawBatchQuery;
import com.stone.mill.dto.Views.PageResult;
import com.stone.mill.dto.Views.SawBatchListItem;
import com.stone.mill.dto.Views.SawBatchView;
import com.stone.mill.dto.Views.SawRegisterResult;
import com.stone.mill.dto.Views.SawSlabTrace;
import com.stone.mill.service.SawBatchService;

import jakarta.validation.Valid;

/**
 * 模块八~十二：锯切批次交接与产出谱系。
 *
 * <p>批次列表 / 开锯建批 / 逐片登记（幂等）/ 交接 / 差异逐项核清 / 收口；
 * 另提供工单维度批次清单、大板反查谱系。
 */
@RestController
@RequestMapping("/api/saw-batches")
public class SawBatchController {

    private final SawBatchService sawBatchService;

    public SawBatchController(SawBatchService sawBatchService) {
        this.sawBatchService = sawBatchService;
    }

    @GetMapping
    public ApiResponse<PageResult<SawBatchListItem>> page(SawBatchQuery query) {
        return ApiResponse.success(sawBatchService.page(query));
    }

    @GetMapping("/by-order")
    public ApiResponse<java.util.List<SawBatchListItem>> byOrder(@RequestParam("orderId") Long orderId) {
        return ApiResponse.success(sawBatchService.listByOrder(orderId));
    }

    @GetMapping("/{id}")
    public ApiResponse<SawBatchView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(sawBatchService.detail(id));
    }

    /** 大板反查：所属批次 + 历次更正（大板详情页用）。 */
    @GetMapping("/trace-by-slab")
    public ApiResponse<SawSlabTrace> traceBySlab(@RequestParam("slabId") Long slabId) {
        return ApiResponse.success(sawBatchService.slabTrace(slabId));
    }

    /** 开锯建批：冻结荒料 / 工单 / 锯切方式 / 排板顺序，并固化工单基线完成数。 */
    @PostMapping
    public ApiResponse<SawBatchView> create(@Valid @RequestBody SawBatchCreateReq req) {
        return ApiResponse.success("锯切批次已建立，排板顺序已冻结", sawBatchService.create(req));
    }

    /**
     * 逐片产出登记。断网重试 / 重复扫码（同一 clientToken）返回 duplicated=true 和首次产出，
     * 不生成两份；大板已归属于其他批次时返回 409 并回带当前归属。
     */
    @PostMapping("/{id}/outputs")
    public ApiResponse<SawRegisterResult> register(@PathVariable("id") Long id,
                                                   @Valid @RequestBody SawOutputRegisterReq req) {
        SawRegisterResult result = sawBatchService.registerOutput(id, req);
        return ApiResponse.success(result.getMessage(), result);
    }

    /** 班组交接：比对冻结计划与逐片实产，有差异进入「差异待核」，现场记录不丢弃。 */
    @PostMapping("/{id}/handover")
    public ApiResponse<SawBatchView> handover(@PathVariable("id") Long id,
                                              @RequestBody(required = false) SawHandoverReq req) {
        SawBatchView view = sawBatchService.submitHandover(id, req == null ? new SawHandoverReq() : req);
        String msg = view.getOpenDiffCount() > 0
                ? "交接已完成，发现 " + view.getOpenDiffCount() + " 条差异进入待核（差异核清前不收口、不计完成数）"
                : "交接完成，计划实产无差异，可收口";
        return ApiResponse.success(msg, view);
    }

    /** 差异逐项核清：CONFIRM 确认实际结果 / CORRECT_ASSIGN 更正归属 / CORRECT_VOID 作废。 */
    @PostMapping("/discrepancies/{diffId}/resolve")
    public ApiResponse<SawBatchView> resolve(@PathVariable("diffId") Long diffId,
                                             @Valid @RequestBody SawDiffResolveReq req) {
        SawBatchView view = sawBatchService.resolveDiff(diffId, req);
        String msg = switch (req.getAction()) {
            case "CONFIRM" -> "已确认实际结果（原因已留痕）";
            case "CORRECT_ASSIGN" -> "归属已更正，原批次与目标批次差异已重算（变更前后已留痕）";
            case "CORRECT_VOID" -> "产出已作废，记录保留（变更前后已留痕）";
            default -> "差异已核清";
        };
        String tail = view.getOpenDiffCount() > 0
                ? "；还剩 " + view.getOpenDiffCount() + " 条差异待核"
                : (view.getHandoverAt() != null ? "；差异全部核清，批次可收口" : "");
        return ApiResponse.success(msg + tail, view);
    }

    /** 收口：差异全部核清后一次性固化谱系并按合格实产推进工单（并发/重复只形成一个结果）。 */
    @PostMapping("/{id}/close")
    public ApiResponse<SawBatchView> close(@PathVariable("id") Long id,
                                           @RequestBody(required = false) SawCloseReq req) {
        return ApiResponse.success("批次已收口，产出谱系已固化，工单按合格实产推进",
                sawBatchService.close(id, req == null ? new SawCloseReq() : req));
    }
}
