package com.lky.kaipay.refund.api;

import com.lky.kaipay.common.api.ApiResponse;
import com.lky.kaipay.refund.api.dto.CreateRefundRequest;
import com.lky.kaipay.refund.api.dto.RefundResponse;
import com.lky.kaipay.refund.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/payments/{paymentId}/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    @PostMapping
    public ResponseEntity<ApiResponse<RefundResponse>> createRefund(
            @RequestHeader(name = "X-Merchant-Id") UUID merchantId,
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @PathVariable("paymentId") UUID paymentId,
            @Valid @RequestBody CreateRefundRequest request
    ) {
        log.info("Received refund request for payment {} by merchant {} with idempotency key {}", paymentId, merchantId, idempotencyKey);
        RefundResponse response = refundService.createRefund(merchantId, paymentId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Refund created successfully", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RefundResponse>>> getPaymentRefunds(
            @RequestHeader(name = "X-Merchant-Id") UUID merchantId,
            @PathVariable("paymentId") UUID paymentId
    ) {
        log.info("Fetching refunds for payment {} and merchant {}", paymentId, merchantId);
        List<RefundResponse> responses = refundService.getPaymentRefunds(merchantId, paymentId);
        return ResponseEntity.ok(ApiResponse.success("Refunds retrieved successfully", responses));
    }
}
