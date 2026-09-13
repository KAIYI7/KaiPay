package com.lky.kaipay.refund.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lky.kaipay.refund.domain.Refund;
import com.lky.kaipay.refund.domain.RefundStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RefundResponse {

    private UUID id;
    private UUID paymentId;
    private UUID merchantId;
    private long amountCents;
    private String currency;
    private RefundStatus status;
    private String reason;
    private String idempotencyKey;
    private Instant createdAt;

    public static RefundResponse fromEntity(Refund refund) {
        if (refund == null) {
            return null;
        }
        return RefundResponse.builder()
                .id(refund.getId())
                .paymentId(refund.getPayment() != null ? refund.getPayment().getId() : null)
                .merchantId(refund.getMerchant() != null ? refund.getMerchant().getId() : null)
                .amountCents(refund.getAmountCents())
                .currency(refund.getCurrency())
                .status(refund.getStatus())
                .reason(refund.getReason())
                .idempotencyKey(refund.getIdempotencyKey())
                .createdAt(refund.getCreatedAt())
                .build();
    }

    public static RefundResponse fromDomain(Refund refund) {
        return fromEntity(refund);
    }
}
