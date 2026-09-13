package com.lky.kaipay.payment.domain.event;

import com.lky.kaipay.payment.domain.Payment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class PaymentInitiatedEvent {

    private UUID paymentId;
    private UUID merchantId;
    private UUID customerId;
    private long amountCents;
    private String currency;
    private String idempotencyKey;

    public static PaymentInitiatedEvent fromPayment(Payment payment) {
        if (payment == null) {
            return null;
        }
        return PaymentInitiatedEvent.builder()
                .paymentId(payment.getId())
                .merchantId(payment.getMerchant() != null ? payment.getMerchant().getId() : null)
                .customerId(payment.getCustomer() != null ? payment.getCustomer().getId() : null)
                .amountCents(payment.getAmountCents() != null ? payment.getAmountCents() : 0L)
                .currency(payment.getCurrency())
                .idempotencyKey(payment.getIdempotencyKey())
                .build();
    }
}
