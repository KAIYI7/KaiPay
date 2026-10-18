package com.lky.kaipay.common;

import com.lky.kaipay.common.exception.InvalidRequestException;

/** New simulated activity uses integer MYR minor units: 100 sen = RM1.00.
 * Existing amountCents/amount_cents names are retained for compatibility. */
public final class MoneyCurrency {
    public static final String MYR = "MYR";

    private MoneyCurrency() {}

    public static void requireMyr(String currency) {
        if (!MYR.equals(currency)) {
            throw new InvalidRequestException("Only MYR is supported for new financial activity");
        }
    }
}
