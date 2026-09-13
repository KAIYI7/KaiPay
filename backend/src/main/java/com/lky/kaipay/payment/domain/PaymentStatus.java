package com.lky.kaipay.payment.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    CREATED,
    PROCESSING,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    DECLINED,
    REFUND_PENDING,
    PARTIALLY_REFUNDED,
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            CREATED, EnumSet.of(PROCESSING, FAILED),
            PROCESSING, EnumSet.of(AUTHORIZED, FAILED, DECLINED),
            AUTHORIZED, EnumSet.of(CAPTURED, FAILED),
            CAPTURED, EnumSet.of(REFUND_PENDING, PARTIALLY_REFUNDED, REFUNDED),
            REFUND_PENDING, EnumSet.of(REFUNDED, CAPTURED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            FAILED, Collections.emptySet(),
            DECLINED, Collections.emptySet(),
            REFUNDED, Collections.emptySet()
    );

    public boolean canTransitionTo(PaymentStatus nextStatus) {
        if (this == nextStatus) {
            return true; // No-op idempotent transition
        }
        return ALLOWED_TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(nextStatus);
    }

    public boolean isTerminal() {
        return this == FAILED || this == DECLINED || this == REFUNDED;
    }
}
