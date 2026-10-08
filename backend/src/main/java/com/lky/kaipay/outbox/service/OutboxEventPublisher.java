package com.lky.kaipay.outbox.service;

import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class OutboxEventPublisher {

    private final PaymentEventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;

    @Autowired
    public OutboxEventPublisher(PaymentEventOutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this(outboxRepository, kafkaTemplate, Clock.systemUTC());
    }

    OutboxEventPublisher(PaymentEventOutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
    }

    @Value("${kaipay.kafka.topics.payment-requests:kaipay.payment.requests}")
    private String topic = "kaipay.payment.requests";

    @Value("${kaipay.outbox.batch-size:20}")
    private int batchSize = 20;

    @Value("${kaipay.outbox.retry-initial-delay-ms:1000}")
    private long initialDelayMs = 1000;

    @Value("${kaipay.outbox.retry-max-delay-ms:60000}")
    private long maxDelayMs = 60000;

    @Value("${kaipay.outbox.send-timeout-ms:4000}")
    private long sendTimeoutMs = 4000;

    @PostConstruct
    void validateConfiguration() {
        if (batchSize <= 0 || initialDelayMs <= 0 || maxDelayMs < initialDelayMs
                || maxDelayMs > 86400000 || sendTimeoutMs <= 0) {
            throw new IllegalArgumentException("Outbox requires a positive batch size/send timeout and 0 < initial retry delay <= maximum <= 24h");
        }
    }

    @Scheduled(fixedDelayString = "${kaipay.outbox.poll-interval-ms:500}")
    @Transactional
    public int publishPendingEvents() {
        List<PaymentEventOutbox> pendingRecords = outboxRepository.findPendingEventsForUpdate(batchSize, clock.instant());
        if (pendingRecords.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (PaymentEventOutbox record : pendingRecords) {
            Instant attemptedAt = clock.instant();
            try {
                String key = record.getAggregateId();
                kafkaTemplate.send(topic, key, record.getPayload()).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Throwable failure = unwrapSendFailure(e);
                log.error("Failed to publish outbox event [id={}, aggregateId={}]: {}",
                        record.getId(), record.getAggregateId(), failure.getMessage(), e);
                String error = failure.getClass().getName() + ": " + failure.getMessage();
                // Only this record-specific rejection proves it cannot be delivered unchanged.
                // Timeouts, auth/config failures and unknown errors retain indefinite retry eligibility.
                if (failure instanceof RecordTooLargeException) {
                    record.quarantine(error, attemptedAt);
                    log.error("Quarantined outbox event [id={}] for operator remediation", record.getId());
                } else {
                    record.scheduleRetry(error, attemptedAt, clock.instant().plusMillis(retryDelayMs(record.getRetryCount())));
                }
                outboxRepository.save(record);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            // Persistence errors must escape and roll back, never be classified as Kafka send errors.
            record.setLastAttemptAt(attemptedAt);
            record.markPublished(clock.instant());
            outboxRepository.save(record);
            publishedCount++;
            log.info("Published outbox event [id={}, aggregateId={}, eventType={}] to topic [{}]",
                    record.getId(), record.getAggregateId(), record.getEventType(), topic);
        }
        return publishedCount;
    }

    private Throwable unwrapSendFailure(Throwable failure) {
        while ((failure instanceof ExecutionException || failure instanceof CompletionException
                || failure instanceof org.springframework.kafka.KafkaException)
                && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    private long retryDelayMs(int previousFailures) {
        long delay = initialDelayMs;
        for (int i = 0; i < previousFailures && delay < maxDelayMs; i++) {
            delay = delay > maxDelayMs / 2 ? maxDelayMs : Math.min(maxDelayMs, delay * 2);
        }
        return delay;
    }
}
