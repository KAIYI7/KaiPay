package com.lky.kaipay.outbox.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutboxAdminController Integration Tests")
class OutboxAdminControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentEventOutboxRepository outboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /v1/events/outbox should return paginated list of outbox events")
    void testListOutboxEvents_Paginated() throws Exception {
        for (int i = 1; i <= 5; i++) {
            UUID aggregateId = UUID.randomUUID();
            PaymentEventOutbox event = PaymentEventOutbox.builder()
                    .aggregateType("PAYMENT")
                    .aggregateId(aggregateId.toString())
                    .eventType("PaymentInitiatedEvent")
                    .payload("{\"paymentId\":\"" + aggregateId + "\",\"index\":" + i + "}")
                    .headers(Map.of("traceId", "trace-" + i, "merchantId", UUID.randomUUID().toString()))
                    .status(PaymentEventOutboxStatus.PENDING)
                    .build();
            outboxRepository.save(event);
        }

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/events/outbox?page=0&size=2",
                HttpMethod.GET,
                null,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("success").asBoolean()).isTrue();

        JsonNode data = root.get("data");
        assertThat(data.get("totalElements").asInt()).isEqualTo(5);
        assertThat(data.get("totalPages").asInt()).isEqualTo(3);
        assertThat(data.get("size").asInt()).isEqualTo(2);
        assertThat(data.get("number").asInt()).isEqualTo(0);
        assertThat(data.get("content").size()).isEqualTo(2);

        JsonNode firstItem = data.get("content").get(0);
        assertThat(firstItem.get("id")).isNotNull();
        assertThat(firstItem.get("aggregateType").asText()).isEqualTo("PAYMENT");
        assertThat(firstItem.get("eventType").asText()).isEqualTo("PaymentInitiatedEvent");
        assertThat(firstItem.get("status").asText()).isEqualTo(PaymentEventOutboxStatus.PENDING.name());
        assertThat(firstItem.get("payload").asText()).contains("paymentId");
        assertThat(firstItem.get("headers")).isNotNull();
        assertThat(firstItem.get("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("GET /v1/events/outbox?status=... should filter events by status")
    void testListOutboxEvents_FilterByStatus() throws Exception {
        // Create 2 PENDING, 3 PUBLISHED
        for (int i = 1; i <= 2; i++) {
            PaymentEventOutbox event = PaymentEventOutbox.builder()
                    .aggregateType("PAYMENT")
                    .aggregateId(UUID.randomUUID().toString())
                    .eventType("PaymentInitiatedEvent")
                    .payload("{\"event\":\"pending-" + i + "\"}")
                    .headers(Map.of("test", "true"))
                    .status(PaymentEventOutboxStatus.PENDING)
                    .build();
            outboxRepository.save(event);
        }

        for (int i = 1; i <= 3; i++) {
            PaymentEventOutbox event = PaymentEventOutbox.builder()
                    .aggregateType("PAYMENT")
                    .aggregateId(UUID.randomUUID().toString())
                    .eventType("PaymentCompletedEvent")
                    .payload("{\"event\":\"published-" + i + "\"}")
                    .headers(Map.of("test", "true"))
                    .status(PaymentEventOutboxStatus.PUBLISHED)
                    .build();
            outboxRepository.save(event);
        }

        // Filter by PENDING
        ResponseEntity<String> pendingResponse = restTemplate.exchange(
                "/v1/events/outbox?status=PENDING",
                HttpMethod.GET,
                null,
                String.class
        );
        assertThat(pendingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode pendingData = objectMapper.readTree(pendingResponse.getBody()).get("data");
        assertThat(pendingData.get("totalElements").asInt()).isEqualTo(2);
        for (JsonNode node : pendingData.get("content")) {
            assertThat(node.get("status").asText()).isEqualTo(PaymentEventOutboxStatus.PENDING.name());
        }

        // Filter by PUBLISHED
        ResponseEntity<String> publishedResponse = restTemplate.exchange(
                "/v1/events/outbox?status=PUBLISHED",
                HttpMethod.GET,
                null,
                String.class
        );
        assertThat(publishedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode publishedData = objectMapper.readTree(publishedResponse.getBody()).get("data");
        assertThat(publishedData.get("totalElements").asInt()).isEqualTo(3);
        for (JsonNode node : publishedData.get("content")) {
            assertThat(node.get("status").asText()).isEqualTo(PaymentEventOutboxStatus.PUBLISHED.name());
        }
    }
}
