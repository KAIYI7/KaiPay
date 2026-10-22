package com.lky.kaipay.reconciliation.core;

import java.time.Instant;
import java.util.*;

/** Bounded redacted projection; contains no payment tokens, references, arbitrary messages or foreign owner values. */
final class AuthorizationSource {
    UUID paymentId;
    long amount, version;
    String currency, status;
    Instant createdAt, updatedAt;
    boolean operationPresent, merchantMatches, validShape;
    String namespace, operationCurrency, disposition, safeCode;
    Integer contractVersion;
    Long operationAmount, evaluationCount;
    Instant operationCreatedAt, operationUpdatedAt, resultAt;
    boolean referencePresent, failureCodePresent, failureMessagePresent, referenceMatches, codeMatches, messageMatches;
    List<UUID> eventIds = new ArrayList<>(), markerEventIds = new ArrayList<>();
    boolean contextIncomplete;
}
