package com.lky.kaipay.payment.service.acquirer;

import com.lky.kaipay.common.exception.GatewayTimeoutException;
import com.lky.kaipay.common.exception.NonRetryableGatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class MockBankAcquirerClient {

    private final Map<UUID, AcquirerAuthorizationResult> authorizationLedger = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> callCounts = new ConcurrentHashMap<>();

    public AcquirerAuthorizationResult authorize(UUID paymentId, long amountCents, String currency) {
        int attempt = callCounts.computeIfAbsent(paymentId, k -> new AtomicInteger()).incrementAndGet();

        if (authorizationLedger.containsKey(paymentId)) {
            log.info("Gateway idempotency hit for paymentId {}. Replaying authorization result.", paymentId);
            return authorizationLedger.get(paymentId);
        }

        // Deterministic failure triggers based on test amounts:
        if (amountCents == 888800L) { // $8,888.00 -> Timeout on attempts 1 & 2, success on attempt 3
            if (attempt < 3) {
                log.warn("Simulated transient gateway timeout for paymentId {} on attempt {}", paymentId, attempt);
                throw new GatewayTimeoutException("Simulated gateway timeout on attempt " + attempt);
            }
            log.info("Simulated gateway recovered on attempt {} for paymentId {}", attempt, paymentId);
            AcquirerAuthorizationResult result = AcquirerAuthorizationResult.approved("AUTH-RECOVERED-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
            authorizationLedger.put(paymentId, result);
            return result;
        }

        if (amountCents == 777700L) { // $7,777.00 -> Continuous timeout (retry exhaustion)
            log.warn("Simulated persistent gateway timeout for paymentId {} on attempt {}", paymentId, attempt);
            throw new GatewayTimeoutException("Simulated persistent gateway timeout on attempt " + attempt);
        }

        if (amountCents == 666600L) { // $6,666.00 -> Non-retryable fatal error
            log.warn("Simulated fatal non-retryable error for paymentId {}", paymentId);
            throw new NonRetryableGatewayException("Simulated fatal non-retryable acquirer error");
        }

        if (amountCents >= 999900L) { // $9,999.00 -> Deterministic business decline
            AcquirerAuthorizationResult result = AcquirerAuthorizationResult.declined("INSUFFICIENT_FUNDS", "Simulated card decline: insufficient funds");
            authorizationLedger.put(paymentId, result);
            return result;
        }

        // Default Success
        AcquirerAuthorizationResult result = AcquirerAuthorizationResult.approved("AUTH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        authorizationLedger.put(paymentId, result);
        return result;
    }

    public int getExecutionCount(UUID paymentId) {
        AtomicInteger count = callCounts.get(paymentId);
        return count != null ? count.get() : 0;
    }

    public int getUniqueAuthorizationsCount() {
        return authorizationLedger.size();
    }

    public void clearLedger() {
        authorizationLedger.clear();
        callCounts.clear();
    }
}
