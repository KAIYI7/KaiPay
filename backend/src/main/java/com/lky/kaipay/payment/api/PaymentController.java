package com.lky.kaipay.payment.api;

import com.lky.kaipay.common.api.ApiResponse;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.api.dto.PaymentResponse;
import com.lky.kaipay.payment.service.PaymentService;
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

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> createPayment(
            @RequestHeader(name = "X-Merchant-Id") UUID merchantId,
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        log.info("Received payment creation request for merchant {} with idempotency key {}", merchantId, idempotencyKey);
        PaymentResponse response = paymentService.createPayment(merchantId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Payment created successfully", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<org.springframework.data.domain.Page<PaymentResponse>>> listPayments(
            @RequestHeader(name = "X-Merchant-Id") UUID merchantId,
            @org.springframework.web.bind.annotation.RequestParam(name = "status", required = false) com.lky.kaipay.payment.domain.PaymentStatus status,
            @org.springframework.web.bind.annotation.RequestParam(name = "page", defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(name = "size", defaultValue = "10") int size,
            @org.springframework.web.bind.annotation.RequestParam(name = "sortBy", defaultValue = "createdAt") String sortBy,
            @org.springframework.web.bind.annotation.RequestParam(name = "direction", defaultValue = "desc") String direction
    ) {
        log.info("Listing payments for merchant {} with status filter: {}, page: {}, size: {}", merchantId, status, page, size);
        org.springframework.data.domain.Sort sort = direction.equalsIgnoreCase("asc")
                ? org.springframework.data.domain.Sort.by(sortBy).ascending()
                : org.springframework.data.domain.Sort.by(sortBy).descending();
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size, sort);
        org.springframework.data.domain.Page<PaymentResponse> response = paymentService.listPayments(merchantId, status, pageable);
        return ResponseEntity.ok(ApiResponse.success("Payments retrieved successfully", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(
            @RequestHeader(name = "X-Merchant-Id") UUID merchantId,
            @PathVariable("id") UUID paymentId
    ) {
        log.info("Fetching payment {} for merchant {}", paymentId, merchantId);
        PaymentResponse response = paymentService.getPayment(merchantId, paymentId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<ApiResponse<PaymentResponse>> capturePayment(
            @RequestHeader(name = "X-Merchant-Id") UUID merchantId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable("id") UUID paymentId
    ) {
        log.info("Received capture request for payment {} by merchant {}", paymentId, merchantId);
        PaymentResponse response = paymentService.capturePayment(merchantId, paymentId, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Payment captured successfully", response));
    }
}
