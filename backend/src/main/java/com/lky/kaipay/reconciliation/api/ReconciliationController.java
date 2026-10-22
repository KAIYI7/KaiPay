package com.lky.kaipay.reconciliation.api;

import com.lky.kaipay.common.api.ApiResponse;
import com.lky.kaipay.reconciliation.api.ReconciliationViews.*;
import com.lky.kaipay.reconciliation.service.ReconciliationQueryService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.Map;

@RestController
@RequestMapping("/v1/reconciliation/runs")
public class ReconciliationController {
    private final ReconciliationQueryService queries;
    public ReconciliationController(ReconciliationQueryService queries) { this.queries = queries; }
    @GetMapping public ApiResponse<Page<RunView>> runs(@RequestHeader(value = "X-Merchant-Id", required = false) UUID merchant,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam Map<String, String> parameters) {
        ReconciliationQueryService.parameters(parameters, false, false);
        return ApiResponse.success(queries.runs(merchant, page, size, false));
    }
    @GetMapping("/{runId}") public ApiResponse<RunView> detail(@RequestHeader(value = "X-Merchant-Id", required = false) UUID merchant, @PathVariable UUID runId,
            @RequestParam Map<String, String> parameters) {
        if (!parameters.isEmpty()) throw new com.lky.kaipay.common.exception.InvalidRequestException("Unsupported reconciliation query parameter");
        return ApiResponse.success(queries.detail(merchant, runId, false));
    }
    @GetMapping("/{runId}/observations") public ApiResponse<Page<ObservationView>> observations(
            @RequestHeader(value = "X-Merchant-Id", required = false) UUID merchant, @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String primaryClass, @RequestParam(required = false) UUID paymentId,
            @RequestParam Map<String, String> parameters) {
        ReconciliationQueryService.parameters(parameters, false, true);
        return ApiResponse.success(queries.observations(merchant, runId, page, size, primaryClass, paymentId, false));
    }
}
