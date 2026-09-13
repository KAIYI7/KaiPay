package com.lky.kaipay.dlt.repository;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DeadLetterEventRepository Integration Tests")
class DeadLetterEventRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DeadLetterEventRepository deadLetterEventRepository;

    @BeforeEach
    void setUp() {
        deadLetterEventRepository.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("Persist dead letter event and retrieve by ID")
    void testPersistAndFindById() {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        DeadLetterEvent dltEvent = DeadLetterEvent.builder()
                .originalTopic("kaipay.payment.requests")
                .originalPartition(1)
                .originalOffset(42L)
                .eventId(eventId)
                .paymentId(paymentId)
                .exceptionClass("com.lky.kaipay.common.exception.GatewayTimeoutException")
                .failureMessage("Connection timed out after 3 retries")
                .retryCount(3)
                .payload("{\"paymentId\":\"" + paymentId + "\",\"amountCents\":5000}")
                .headers(Map.of("correlationId", "corr-123"))
                .build();

        DeadLetterEvent saved = deadLetterEventRepository.saveAndFlush(dltEvent);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        DeadLetterEvent found = deadLetterEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getOriginalTopic()).isEqualTo("kaipay.payment.requests");
        assertThat(found.getOriginalPartition()).isEqualTo(1);
        assertThat(found.getOriginalOffset()).isEqualTo(42L);
        assertThat(found.getEventId()).isEqualTo(eventId);
        assertThat(found.getPaymentId()).isEqualTo(paymentId);
        assertThat(found.getExceptionClass()).isEqualTo("com.lky.kaipay.common.exception.GatewayTimeoutException");
        assertThat(found.getFailureMessage()).isEqualTo("Connection timed out after 3 retries");
        assertThat(found.getRetryCount()).isEqualTo(3);
        assertThat(found.getPayload()).contains("5000");
    }

    @Test
    @Transactional
    @DisplayName("findByPaymentIdOrderByCreatedAtDesc returns dead letter events ordered descending")
    void testFindByPaymentIdOrderByCreatedAtDesc() throws InterruptedException {
        UUID paymentId = UUID.randomUUID();

        DeadLetterEvent event1 = DeadLetterEvent.builder()
                .originalTopic("kaipay.payment.requests")
                .originalPartition(0)
                .originalOffset(10L)
                .paymentId(paymentId)
                .exceptionClass("com.lky.kaipay.common.exception.GatewayTimeoutException")
                .failureMessage("Attempt 1 failure")
                .payload("{\"paymentId\":\"" + paymentId + "\"}")
                .build();
        deadLetterEventRepository.saveAndFlush(event1);

        Thread.sleep(50);

        DeadLetterEvent event2 = DeadLetterEvent.builder()
                .originalTopic("kaipay.payment.requests")
                .originalPartition(0)
                .originalOffset(11L)
                .paymentId(paymentId)
                .exceptionClass("com.lky.kaipay.common.exception.GatewayUnavailableException")
                .failureMessage("Attempt 2 failure")
                .payload("{\"paymentId\":\"" + paymentId + "\"}")
                .build();
        deadLetterEventRepository.saveAndFlush(event2);

        List<DeadLetterEvent> results = deadLetterEventRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getFailureMessage()).isEqualTo("Attempt 2 failure");
        assertThat(results.get(1).getFailureMessage()).isEqualTo("Attempt 1 failure");
    }

    @Test
    @Transactional
    @DisplayName("findAllByOrderByCreatedAtDesc with pagination returns correct page")
    void testFindAllByOrderByCreatedAtDescPagination() {
        for (int i = 1; i <= 5; i++) {
            DeadLetterEvent event = DeadLetterEvent.builder()
                    .originalTopic("kaipay.payment.requests")
                    .originalPartition(0)
                    .originalOffset((long) i)
                    .exceptionClass("Exception" + i)
                    .failureMessage("Error " + i)
                    .payload("{\"index\":" + i + "}")
                    .build();
            deadLetterEventRepository.save(event);
        }
        deadLetterEventRepository.flush();

        Page<DeadLetterEvent> page = deadLetterEventRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 3));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    @Transactional
    @DisplayName("findByPaymentIdOrderByCreatedAtDesc with pagination returns correct page")
    void testFindByPaymentIdOrderByCreatedAtDescPagination() {
        UUID paymentId = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            DeadLetterEvent event = DeadLetterEvent.builder()
                    .originalTopic("kaipay.payment.requests")
                    .originalPartition(0)
                    .originalOffset((long) i)
                    .paymentId(paymentId)
                    .exceptionClass("Exception" + i)
                    .failureMessage("Error " + i)
                    .payload("{\"index\":" + i + "}")
                    .build();
            deadLetterEventRepository.save(event);
        }
        deadLetterEventRepository.flush();

        Page<DeadLetterEvent> page = deadLetterEventRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId, PageRequest.of(0, 3));

        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }
}
