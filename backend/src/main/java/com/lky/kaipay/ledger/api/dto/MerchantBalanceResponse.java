package com.lky.kaipay.ledger.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MerchantBalanceResponse {

    private UUID merchantId;
    private long availableBalanceCents;
    private long pendingSettlementCents;
    private long totalVolumeCents;
    private long totalFeesCents;
    private long totalRefundsCents;
    private String currency;
}
