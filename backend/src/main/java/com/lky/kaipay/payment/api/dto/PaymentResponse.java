package com.lky.kaipay.payment.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {
    private final UUID id;
    private final UUID merchantId;
    private final UUID customerId;
    private final UUID paymentMethodId;
    private final Long amountCents;
    private final String currency;
    private final PaymentStatus status;
    private final String idempotencyKey;
    private final String gatewayReference;
    private final String failureCode;
    private final String failureMessage;
    private final Map<String, Object> metadata;
    private final Long version;
    private final Instant createdAt;
    private final Instant updatedAt;

    public static PaymentResponse fromDomain(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId())
                .merchantId(payment.getMerchant().getId())
                .customerId(payment.getCustomer().getId())
                .paymentMethodId(payment.getPaymentMethod() != null ? payment.getPaymentMethod().getId() : null)
                .amountCents(payment.getAmountCents())
                .currency(payment.getCurrency())
                .status(payment.getStatus())
                .idempotencyKey(payment.getIdempotencyKey())
                .gatewayReference(payment.getGatewayReference())
                .failureCode(payment.getFailureCode())
                .failureMessage(payment.getFailureMessage())
                .metadata(payment.getMetadata())
                .version(payment.getVersion())
                .createdAt(payment.getCreatedAt())
                .updatedAt(payment.getUpdatedAt())
                .build();
    }

    public static PaymentResponse fromEntity(Payment payment) {
        return fromDomain(payment);
    }
}
