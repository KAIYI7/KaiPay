package com.lky.kaipay.common.exception;

import org.hibernate.exception.ConstraintViolationException;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Identifies only database constraints that implement an established idempotency contract. */
public final class DatabaseIntegrityClassifier {

    private static final Set<String> IDEMPOTENCY_CONSTRAINTS = Set.of(
            "uk_merchant_idempotency",
            "pk_idempotency_records",
            "uk_refunds_idempotency"
    );

    private DatabaseIntegrityClassifier() {
    }

    public static boolean isIdempotencyConflict(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && violation.getConstraintName() != null
                    && IDEMPOTENCY_CONSTRAINTS.contains(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
