package com.lky.kaipay.refund.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class PaymentRefundedEvent {

    private UUID refundId;
    private UUID paymentId;
    private UUID merchantId;
    private long refundAmountCents;
    private String currency;
    private String reason;
    private Instant refundedAt;
}
