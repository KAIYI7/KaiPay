package com.lky.kaipay.webhook.api;

import com.lky.kaipay.common.api.ApiResponse;
import com.lky.kaipay.webhook.api.WebhookViews.*;
import com.lky.kaipay.webhook.service.WebhookQueryService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/webhooks")
public class WebhookController {
    private final WebhookQueryService queries;
    public WebhookController(WebhookQueryService queries) { this.queries = queries; }

    @GetMapping("/endpoint")
    public ApiResponse<EndpointView> endpoint(@RequestHeader("X-Merchant-Id") UUID merchant) {
        return ApiResponse.success(queries.endpoint(merchant));
    }
    @GetMapping("/deliveries")
    public ApiResponse<Page<DeliveryView>> deliveries(@RequestHeader("X-Merchant-Id") UUID merchant,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) UUID paymentId, @RequestParam(required = false) String state) {
        return ApiResponse.success(queries.deliveries(merchant, page, size, paymentId, state));
    }
    @GetMapping("/deliveries/{id}")
    public ApiResponse<DeliveryDetail> detail(@RequestHeader("X-Merchant-Id") UUID merchant, @PathVariable UUID id) {
        return ApiResponse.success(queries.detail(merchant, id));
    }
}
