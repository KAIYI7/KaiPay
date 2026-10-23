package com.lky.kaipay.ledger.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An interval selects roots; the report compares current retained records, not past balances. */
public record AccountingAuditRequest(UUID merchantId, Instant fromInclusive, Instant cutoffExclusive) {
    public AccountingAuditRequest {
        Objects.requireNonNull(merchantId);
        Objects.requireNonNull(fromInclusive);
        Objects.requireNonNull(cutoffExclusive);
        if (!fromInclusive.isBefore(cutoffExclusive)
                || Duration.between(fromInclusive, cutoffExclusive).compareTo(Duration.ofDays(7)) > 0
                || fromInclusive.getNano() % 1000 != 0 || cutoffExclusive.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Invalid accounting audit interval");
        }
    }
}
