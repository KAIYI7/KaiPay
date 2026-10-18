package com.lky.kaipay.payment.service.acquirer;

import java.util.UUID;

/** Immutable persisted-payment snapshot, created only after event equivalence validation. */
public record AuthorizationRequest(UUID paymentId, UUID merchantId, long amountCents, String currency) {
    public static final String NAMESPACE = "kaipay-sim-auth-v1";
    public static final int CONTRACT_VERSION = 1;

    public AuthorizationRequest {
        if (paymentId == null || merchantId == null || amountCents <= 0
                || currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid simulated authorization request");
        }
    }
}
