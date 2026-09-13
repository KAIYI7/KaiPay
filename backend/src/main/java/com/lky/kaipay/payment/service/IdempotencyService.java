package com.lky.kaipay.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.exception.IdempotencyConflictException;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.api.dto.PaymentResponse;
import com.lky.kaipay.payment.domain.IdempotencyRecord;
import com.lky.kaipay.payment.domain.IdempotencyRecordId;
import com.lky.kaipay.payment.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    private static final Duration DEFAULT_EXPIRATION = Duration.ofHours(24);

    public String computeRequestHash(CreatePaymentRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String normalizedPayload = String.format(
                    "amount=%d;currency=%s;customer=%s;method=%s;metadata=%s",
                    request.getAmountCents(),
                    request.getCurrency(),
                    request.getCustomerId(),
                    request.getPaymentMethodId() != null ? request.getPaymentMethodId() : "",
                    request.getMetadata() != null ? request.getMetadata().toString() : ""
            );
            byte[] hashBytes = digest.digest(normalizedPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    @Transactional(readOnly = true)
    public Optional<PaymentResponse> getExistingResponse(UUID merchantId, String idempotencyKey, String requestHash) {
        Optional<IdempotencyRecord> recordOpt = idempotencyRecordRepository.findByMerchantIdAndKey(merchantId, idempotencyKey);

        if (recordOpt.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyRecord record = recordOpt.get();

        if (record.isExpired()) {
            log.info("Idempotency record expired for merchant {} and key {}", merchantId, idempotencyKey);
            return Optional.empty();
        }

        if (!record.getRequestHash().equals(requestHash)) {
            log.warn("Idempotency key mismatch for merchant {} and key {}. Stored hash: {}, Incoming hash: {}",
                    merchantId, idempotencyKey, record.getRequestHash(), requestHash);
            throw new IdempotencyConflictException(
                    String.format("Idempotency key '%s' was already used with a different request payload", idempotencyKey)
            );
        }

        log.info("Idempotent cache hit for merchant {} with key {}", merchantId, idempotencyKey);
        try {
            PaymentResponse cachedResponse = objectMapper.readValue(record.getResponseBody(), PaymentResponse.class);
            return Optional.of(cachedResponse);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize cached idempotency response body", e);
            throw new IllegalStateException("Failed to deserialize cached idempotency response", e);
        }
    }

    @Transactional
    public void recordResponse(UUID merchantId, String idempotencyKey, String requestHash, int statusCode, PaymentResponse response) {
        try {
            String responseJson = objectMapper.writeValueAsString(response);
            IdempotencyRecord record = IdempotencyRecord.builder()
                    .id(new IdempotencyRecordId(merchantId, idempotencyKey))
                    .requestHash(requestHash)
                    .responseStatus(statusCode)
                    .responseBody(responseJson)
                    .expiresAt(Instant.now().plus(DEFAULT_EXPIRATION))
                    .build();

            idempotencyRecordRepository.save(record);
            log.debug("Saved idempotency record for merchant {} and key {}", merchantId, idempotencyKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for idempotency storage", e);
            throw new IllegalStateException("Failed to serialize response for idempotency storage", e);
        }
    }
}
