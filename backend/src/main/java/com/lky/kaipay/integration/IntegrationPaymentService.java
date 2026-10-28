package com.lky.kaipay.integration;

import com.lky.kaipay.common.exception.DatabaseIntegrityClassifier;
import com.lky.kaipay.common.exception.IdempotencyConflictException;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.service.PaymentService;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** No surrounding transaction: the core create commits/rolls back before every recovery read. */
@Service
public class IntegrationPaymentService {
    private final IntegrationPaymentStore store;
    private final PaymentService payments;
    public IntegrationPaymentService(IntegrationPaymentStore store, PaymentService payments) {
        this.store = store;
        this.payments = payments;
    }
    public Projection put(IntegrationProperties.Binding binding, UUID reference, long amount) {
        var before = checkedRead(binding, reference);
        if (before.paymentId() != null) return project(binding, reference, amount, before);
        if (before.cached()) return recover(binding, reference, amount);
        try {
            payments.createPayment(binding.merchantId(), IntegrationPaymentStore.key(binding, reference),
                    CreatePaymentRequest.builder().amountCents(amount).currency("MYR")
                            .customerId(binding.customerId()).paymentMethodId(null).metadata(Map.of()).build());
        } catch (RuntimeException failure) {
            // Never convert unrelated integrity/serialization/transaction failures into success.
            if (!(failure instanceof IdempotencyConflictException)
                    && !DatabaseIntegrityClassifier.isIdempotencyConflict(failure)) throw failure;
            return recover(binding, reference, amount);
        }
        // A core cache hit can win after prelookup. Always validate retained current state.
        return recover(binding, reference, amount);
    }
    public Projection get(IntegrationProperties.Binding binding, UUID reference) {
        var snapshot = checkedRead(binding, reference);
        if (snapshot.paymentId() != null) return project(binding, reference, null, snapshot);
        if (snapshot.cached()) {
            snapshot = checkedRead(binding, reference);
            if (snapshot.paymentId() != null) return project(binding, reference, null, snapshot);
            if (snapshot.cached()) throw new IntegrationFailure(409, "RECOVERY_REQUIRED");
        }
        throw new IntegrationFailure(404, "NOT_FOUND");
    }
    private Projection recover(IntegrationProperties.Binding binding, UUID reference, long amount) {
        var snapshot = checkedRead(binding, reference);
        if (snapshot.paymentId() != null) return project(binding, reference, amount, snapshot);
        // A second fresh single-statement snapshot avoids stale/mixed orphan classification.
        snapshot = checkedRead(binding, reference);
        if (snapshot.paymentId() != null) return project(binding, reference, amount, snapshot);
        if (snapshot.cached()) throw new IntegrationFailure(409, "RECOVERY_REQUIRED");
        throw new IntegrationFailure(503, "TEMPORARILY_UNAVAILABLE");
    }
    private IntegrationPaymentStore.Snapshot checkedRead(IntegrationProperties.Binding binding, UUID reference) {
        var snapshot = store.read(binding, reference);
        if (!snapshot.active()) throw new IntegrationFailure(403, "FORBIDDEN");
        return snapshot;
    }
    private Projection project(IntegrationProperties.Binding binding, UUID reference, Long amount,
                               IntegrationPaymentStore.Snapshot snapshot) {
        if (!binding.customerId().equals(snapshot.customerId()) || snapshot.methodId() != null
                || !snapshot.emptyMetadata() || !"MYR".equals(snapshot.currency()) || snapshot.amount() == null
                || snapshot.amount() < 1 || snapshot.amount() > 1_000_000_000L
                || (amount != null && !amount.equals(snapshot.amount())))
            throw new IntegrationFailure(409, "IDEMPOTENCY_CONFLICT");
        return new Projection(reference, snapshot.paymentId(), snapshot.amount(), "MYR", snapshot.status(),
                snapshot.version(), true, snapshot.updatedAt());
    }
    public record Projection(UUID clientReference, UUID paymentId, long amountCents, String currency,
                             String status, long version, boolean simulation, Instant updatedAt) {}
}
