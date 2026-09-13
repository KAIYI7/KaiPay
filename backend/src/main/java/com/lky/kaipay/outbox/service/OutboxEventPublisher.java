package com.lky.kaipay.outbox.service;

import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxEventPublisher {

    private final PaymentEventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${kaipay.kafka.topics.payment-requests:kaipay.payment.requests}")
    private String topic = "kaipay.payment.requests";

    @Value("${kaipay.outbox.batch-size:20}")
    private int batchSize = 20;

    @Scheduled(fixedDelayString = "${kaipay.outbox.poll-interval-ms:500}")
    @Transactional
    public int publishPendingEvents() {
        List<PaymentEventOutbox> pendingRecords = outboxRepository.findPendingEventsForUpdate(batchSize);
        if (pendingRecords.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;
        for (PaymentEventOutbox record : pendingRecords) {
            try {
                String key = record.getAggregateId();
                kafkaTemplate.send(topic, key, record.getPayload()).get(2, TimeUnit.SECONDS);
                record.markPublished();
                outboxRepository.save(record);
                publishedCount++;
                log.info("Published outbox event [id={}, aggregateId={}, eventType={}] to topic [{}]",
                        record.getId(), record.getAggregateId(), record.getEventType(), topic);
            } catch (Exception e) {
                log.error("Failed to publish outbox event [id={}, aggregateId={}]: {}",
                        record.getId(), record.getAggregateId(), e.getMessage(), e);
                record.recordError(e.getMessage());
                outboxRepository.save(record);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                break;
            }
        }
        return publishedCount;
    }
}
