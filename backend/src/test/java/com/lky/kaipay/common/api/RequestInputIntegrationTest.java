package com.lky.kaipay.common.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.ledger.repository.AccountRepository;
import com.lky.kaipay.ledger.repository.JournalRepository;
import com.lky.kaipay.ledger.repository.LedgerEntryRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.repository.IdempotencyRecordRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.refund.repository.RefundRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestInputIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SECRET = "private-input-marker";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private PaymentRepository payments;
    @Autowired private PaymentEventOutboxRepository outbox;
    @Autowired private RefundRepository refunds;
    @Autowired private IdempotencyRecordRepository idempotency;
    @Autowired private AccountRepository accounts;
    @Autowired private JournalRepository journals;
    @Autowired private LedgerEntryRepository entries;

    private Merchant merchant;
    private long[] originalCounts;

    @BeforeEach
    void setUp() {
        merchant = merchantRepository.save(Merchant.builder()
                .name("Request Input Merchant")
                .apiKeyHash("request-input-" + UUID.randomUUID())
                .status(MerchantStatus.ACTIVE)
                .build());
        originalCounts = businessCounts();
    }

    @AfterEach
    void tearDown() {
        merchantRepository.deleteById(merchant.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "missing", "wrongType"})
    void unreadablePaymentBodyReturnsSanitizedBadRequestWithoutWrites(String kind) throws Exception {
        String body = switch (kind) {
            case "malformed" -> "{\"metadata\":{\"secret\":\"" + SECRET + "\"},";
            case "missing" -> null;
            case "wrongType" -> "{\"amountCents\":\"" + SECRET + "\",\"currency\":\"MYR\",\"customerId\":\""
                    + UUID.randomUUID() + "\"}";
            default -> throw new AssertionError(kind);
        };
        ResponseEntity<String> response = restTemplate.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(body, headers()), String.class);

        assertBadRequest(response, "Request body is missing or malformed", "/v1/payments");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/payments/not-a-uuid",
            "/v1/payments?status=private-input-marker",
            "/v1/payments?page=private-input-marker",
            "/v1/payments?size=2147483648",
            "/v1/events/dlt?paymentId=private-input-marker",
            "/v1/ledger/journals?page=private-input-marker"
    })
    void invalidPathOrQueryTypeReturnsSanitizedBadRequestWithoutWrites(String route) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(route, HttpMethod.GET,
                new HttpEntity<Void>(route.startsWith("/v1/events/") ? security().adminHeaders() : headers()), String.class);

        assertBadRequest(response, "Request parameter has an invalid value", route.split("\\?")[0]);
    }

    @Test
    void invalidMerchantHeaderReturnsSanitizedBadRequestWithoutWrites() throws Exception {
        HttpHeaders headers = headers();
        headers.set("X-Merchant-Id", SECRET);
        ResponseEntity<String> response = restTemplate.exchange("/v1/payments", HttpMethod.GET,
                new HttpEntity<Void>(headers), String.class);

        assertBadRequest(response, "Request parameter has an invalid value", "/v1/payments");
    }

    @Test
    void validEnumAndNumericParametersStillReturnSuccessfulListWithoutWrites() throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/v1/payments?status=CREATED&page=0&size=2",
                HttpMethod.GET, new HttpEntity<Void>(headers()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("success").asBoolean()).isTrue();
        assertThat(root.get("data").get("totalElements").asLong()).isZero();
        assertThat(businessCounts()).containsExactly(originalCounts);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = security().merchantHeaders(merchant.getId());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Id", merchant.getId().toString());
        headers.set("Idempotency-Key", "request-input-" + UUID.randomUUID());
        return headers;
    }

    private void assertBadRequest(ResponseEntity<String> response, String message, String path) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = objectMapper.readTree(response.getBody());
        assertThat(error.get("status").asInt()).isEqualTo(400);
        assertThat(error.get("error").asText()).isEqualTo("Bad Request");
        assertThat(error.get("message").asText()).isEqualTo(message);
        assertThat(error.get("path").asText()).isEqualTo(path);
        assertThat(response.getBody()).doesNotContain(SECRET, "java.lang", "com.lky", "Jackson", "Exception");
        assertThat(businessCounts()).containsExactly(originalCounts);
    }

    private long[] businessCounts() {
        return new long[]{payments.count(), outbox.count(), refunds.count(), idempotency.count(),
                accounts.count(), journals.count(), entries.count()};
    }
}
