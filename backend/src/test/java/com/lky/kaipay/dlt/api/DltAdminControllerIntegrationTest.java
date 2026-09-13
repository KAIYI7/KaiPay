package com.lky.kaipay.dlt.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
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

@DisplayName("DltAdminController Integration Tests")
class DltAdminControllerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeadLetterEventRepository deadLetterEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        deadLetterEventRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /v1/events/dlt should return paginated list of dead letter events")
    void testListDeadLetterEvents_Paginated() throws Exception {
        for (int i = 1; i <= 5; i++) {
            UUID eventId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            DeadLetterEvent event = DeadLetterEvent.builder()
                    .originalTopic("kaipay.payment.requests")
                    .originalPartition(0)
                    .originalOffset((long) i)
                    .eventId(eventId)
                    .paymentId(paymentId)
                    .exceptionClass("com.lky.kaipay.common.exception.GatewayTimeoutException")
                    .failureMessage("Gateway timeout on attempt " + i)
                    .retryCount(i)
                    .payload("{\"paymentId\":\"" + paymentId + "\",\"index\":" + i + "}")
                    .headers(Map.of("traceId", "trace-" + i, "attempt", i))
                    .build();
            deadLetterEventRepository.save(event);
        }

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/events/dlt?page=0&size=2",
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
        assertThat(firstItem.get("originalTopic").asText()).isEqualTo("kaipay.payment.requests");
        assertThat(firstItem.get("originalPartition").asInt()).isEqualTo(0);
        assertThat(firstItem.get("originalOffset")).isNotNull();
        assertThat(firstItem.get("eventId")).isNotNull();
        assertThat(firstItem.get("paymentId")).isNotNull();
        assertThat(firstItem.get("exceptionClass").asText()).isEqualTo("com.lky.kaipay.common.exception.GatewayTimeoutException");
        assertThat(firstItem.get("failureMessage").asText()).contains("Gateway timeout on attempt");
        assertThat(firstItem.get("retryCount")).isNotNull();
        assertThat(firstItem.get("payload").asText()).contains("paymentId");
        assertThat(firstItem.get("headers")).isNotNull();
        assertThat(firstItem.get("createdAt")).isNotNull();
    }

    @Test
    @DisplayName("GET /v1/events/dlt?paymentId=... should filter events by paymentId")
    void testListDeadLetterEvents_FilterByPaymentId() throws Exception {
        UUID paymentIdA = UUID.randomUUID();
        UUID paymentIdB = UUID.randomUUID();

        // 2 events for paymentIdA
        for (int i = 1; i <= 2; i++) {
            DeadLetterEvent event = DeadLetterEvent.builder()
                    .originalTopic("kaipay.payment.requests")
                    .originalPartition(0)
                    .originalOffset((long) i)
                    .paymentId(paymentIdA)
                    .exceptionClass("com.lky.kaipay.common.exception.GatewayTimeoutException")
                    .failureMessage("Error for payment A - " + i)
                    .payload("{\"paymentId\":\"" + paymentIdA + "\"}")
                    .headers(Map.of("test", "A"))
                    .build();
            deadLetterEventRepository.save(event);
        }

        // 3 events for paymentIdB
        for (int i = 1; i <= 3; i++) {
            DeadLetterEvent event = DeadLetterEvent.builder()
                    .originalTopic("kaipay.payment.requests")
                    .originalPartition(0)
                    .originalOffset((long) (i + 10))
                    .paymentId(paymentIdB)
                    .exceptionClass("com.lky.kaipay.common.exception.GatewayUnavailableException")
                    .failureMessage("Error for payment B - " + i)
                    .payload("{\"paymentId\":\"" + paymentIdB + "\"}")
                    .headers(Map.of("test", "B"))
                    .build();
            deadLetterEventRepository.save(event);
        }

        // Filter by paymentIdA
        ResponseEntity<String> responseA = restTemplate.exchange(
                "/v1/events/dlt?paymentId=" + paymentIdA,
                HttpMethod.GET,
                null,
                String.class
        );
        assertThat(responseA.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode dataA = objectMapper.readTree(responseA.getBody()).get("data");
        assertThat(dataA.get("totalElements").asInt()).isEqualTo(2);
        for (JsonNode node : dataA.get("content")) {
            assertThat(node.get("paymentId").asText()).isEqualTo(paymentIdA.toString());
        }

        // Filter by paymentIdB
        ResponseEntity<String> responseB = restTemplate.exchange(
                "/v1/events/dlt?paymentId=" + paymentIdB,
                HttpMethod.GET,
                null,
                String.class
        );
        assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode dataB = objectMapper.readTree(responseB.getBody()).get("data");
        assertThat(dataB.get("totalElements").asInt()).isEqualTo(3);
        for (JsonNode node : dataB.get("content")) {
            assertThat(node.get("paymentId").asText()).isEqualTo(paymentIdB.toString());
        }

        // Filter by non-existent paymentId
        UUID nonExistentPaymentId = UUID.randomUUID();
        ResponseEntity<String> responseEmpty = restTemplate.exchange(
                "/v1/events/dlt?paymentId=" + nonExistentPaymentId,
                HttpMethod.GET,
                null,
                String.class
        );
        assertThat(responseEmpty.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode dataEmpty = objectMapper.readTree(responseEmpty.getBody()).get("data");
        assertThat(dataEmpty.get("totalElements").asInt()).isEqualTo(0);
        assertThat(dataEmpty.get("content").size()).isEqualTo(0);
    }
}
