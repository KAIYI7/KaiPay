package com.lky.kaipay.outbox.repository;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PaymentEventOutboxRepository Integration Tests")
class PaymentEventOutboxRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PaymentEventOutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("Persist outbox event with JSONB payload and headers, query pending events with FOR UPDATE SKIP LOCKED")
    void persistAndFindPendingEvents() {
        UUID aggregateId1 = UUID.randomUUID();
        UUID aggregateId2 = UUID.randomUUID();

        PaymentEventOutbox event1 = PaymentEventOutbox.builder()
                .aggregateType("PAYMENT")
                .aggregateId(aggregateId1.toString())
                .eventType("PAYMENT_INITIATED")
                .payload("{\"paymentId\":\"" + aggregateId1 + "\",\"amountCents\":5000}")
                .headers(Map.of("traceId", "trace-1", "source", "test"))
                .status(PaymentEventOutboxStatus.PENDING)
                .build();

        PaymentEventOutbox event2 = PaymentEventOutbox.builder()
                .aggregateType("PAYMENT")
                .aggregateId(aggregateId2.toString())
                .eventType("PAYMENT_INITIATED")
                .payload("{\"paymentId\":\"" + aggregateId2 + "\",\"amountCents\":10000}")
                .headers(Map.of("traceId", "trace-2"))
                .status(PaymentEventOutboxStatus.PUBLISHED)
                .build();

        outboxRepository.save(event1);
        outboxRepository.save(event2);

        List<PaymentEventOutbox> pending = outboxRepository.findPendingEventsForUpdate(10);

        assertThat(pending).hasSize(1);
        PaymentEventOutbox retrieved = pending.getFirst();
        assertThat(retrieved.getAggregateId()).isEqualTo(aggregateId1.toString());
        assertThat(retrieved.getEventType()).isEqualTo("PAYMENT_INITIATED");
        assertThat(retrieved.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(retrieved.getPayload()).contains(aggregateId1.toString());
        assertThat(retrieved.getHeaders()).containsEntry("traceId", "trace-1");
        assertThat(retrieved.getCreatedAt()).isNotNull();

        // Mark as published and update
        retrieved.markPublished();
        outboxRepository.save(retrieved);

        List<PaymentEventOutbox> pendingAfterPublish = outboxRepository.findPendingEventsForUpdate(10);
        assertThat(pendingAfterPublish).isEmpty();
    }

    @Test
    @DisplayName("findAllByOrderByCreatedAtDesc and findByStatusOrderByCreatedAtDesc return paginated results")
    void testPaginatedAndStatusFilteredQueries() {
        for (int i = 0; i < 5; i++) {
            PaymentEventOutbox event = PaymentEventOutbox.builder()
                    .aggregateType("PAYMENT")
                    .aggregateId(UUID.randomUUID().toString())
                    .eventType("EVENT_" + i)
                    .payload("{}")
                    .status(i % 2 == 0 ? PaymentEventOutboxStatus.PENDING : PaymentEventOutboxStatus.PUBLISHED)
                    .build();
            outboxRepository.save(event);
        }

        org.springframework.data.domain.Page<PaymentEventOutbox> allEvents = outboxRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 3)
        );
        assertThat(allEvents.getTotalElements()).isEqualTo(5);
        assertThat(allEvents.getContent()).hasSize(3);

        org.springframework.data.domain.Page<PaymentEventOutbox> pendingEvents = outboxRepository.findByStatusOrderByCreatedAtDesc(
                PaymentEventOutboxStatus.PENDING,
                org.springframework.data.domain.PageRequest.of(0, 10)
        );
        assertThat(pendingEvents.getTotalElements()).isEqualTo(3);
        assertThat(pendingEvents.getContent()).allMatch(e -> e.getStatus() == PaymentEventOutboxStatus.PENDING);
    }
}
