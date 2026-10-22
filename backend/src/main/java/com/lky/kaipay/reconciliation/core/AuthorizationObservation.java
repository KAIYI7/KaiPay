package com.lky.kaipay.reconciliation.core;

import java.util.*;

public record AuthorizationObservation(UUID paymentId, String primaryClass, String severity,
                                       List<String> reasonCodes, Map<String,Object> evidence, String explanation) {
    public static final List<String> CLASSES = List.of("OWNERSHIP_CONFLICT","EVIDENCE_INVALID","REQUEST_MISMATCH",
            "AUTHORIZATION_MISMATCH","INTENT_STATE_CONFLICT","PENDING_FINALIZATION","FINALIZATION_OUTSTANDING",
            "UNRESOLVED","INTENT_PENDING","QUEUED","EVIDENCE_UNAVAILABLE","AMBIGUOUS_PROCESSING","MATCHED");
    public AuthorizationObservation {
        reasonCodes = List.copyOf(reasonCodes);
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
        if (!CLASSES.contains(primaryClass) || reasonCodes.size() > 16) throw new IllegalArgumentException("Invalid observation");
    }
}
