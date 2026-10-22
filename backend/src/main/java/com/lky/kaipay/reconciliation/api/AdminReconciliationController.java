package com.lky.kaipay.reconciliation.api;

import com.lky.kaipay.common.api.ApiResponse;
import com.lky.kaipay.reconciliation.api.ReconciliationViews.*;
import com.lky.kaipay.reconciliation.service.ReconciliationQueryService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/reconciliation/runs")
public class AdminReconciliationController {
    private final ReconciliationQueryService queries;
    public AdminReconciliationController(ReconciliationQueryService queries) { this.queries = queries; }
    @GetMapping public ApiResponse<Page<RunView>> runs(@RequestParam(required = false) UUID merchantId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam Map<String, String> parameters) {
        ReconciliationQueryService.parameters(parameters, true, false);
        return ApiResponse.success(queries.runs(merchantId, page, size, true));
    }
    @GetMapping("/{runId}") public ApiResponse<RunView> detail(@RequestParam(required = false) UUID merchantId, @PathVariable UUID runId,
            @RequestParam Map<String, String> parameters) {
        if (!java.util.Set.of("merchantId").containsAll(parameters.keySet()))
            throw new com.lky.kaipay.common.exception.InvalidRequestException("Unsupported reconciliation query parameter");
        return ApiResponse.success(queries.detail(merchantId, runId, true));
    }
    @GetMapping("/{runId}/observations") public ApiResponse<Page<ObservationView>> observations(
            @RequestParam(required = false) UUID merchantId, @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String primaryClass, @RequestParam(required = false) UUID paymentId,
            @RequestParam Map<String, String> parameters) {
        ReconciliationQueryService.parameters(parameters, true, true);
        return ApiResponse.success(queries.observations(merchantId, runId, page, size, primaryClass, paymentId, true));
    }
}
