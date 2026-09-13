package com.lky.kaipay.merchant.api;

import com.lky.kaipay.common.api.ApiResponse;
import com.lky.kaipay.ledger.api.dto.MerchantBalanceResponse;
import com.lky.kaipay.ledger.service.MerchantBalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantBalanceService merchantBalanceService;

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<MerchantBalanceResponse>> getBalance(
            @RequestHeader(name = "X-Merchant-Id") UUID merchantId
    ) {
        log.info("Received direct request for merchant balance for merchant {}", merchantId);
        MerchantBalanceResponse response = merchantBalanceService.getMerchantBalance(merchantId);
        return ResponseEntity.ok(ApiResponse.success("Merchant balance retrieved successfully", response));
    }
}
