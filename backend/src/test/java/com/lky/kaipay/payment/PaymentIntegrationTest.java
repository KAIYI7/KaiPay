package com.lky.kaipay.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentMethod;
import com.lky.kaipay.payment.domain.PaymentMethodStatus;
import com.lky.kaipay.payment.domain.PaymentMethodType;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.IdempotencyRecordRepository;
import com.lky.kaipay.payment.repository.PaymentMethodRepository;
import com.lky.kaipay.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Payment Core & Idempotency Integration Tests")
class PaymentIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PaymentMethodRepository paymentMethodRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private PaymentEventOutboxRepository paymentEventOutboxRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Merchant testMerchant;
    private Customer testCustomer;
    private PaymentMethod testPaymentMethod;

    @BeforeEach
    void setUp() {
        paymentEventOutboxRepository.deleteAll();
        paymentRepository.deleteAll();
        idempotencyRecordRepository.deleteAll();
        paymentMethodRepository.deleteAll();
        customerRepository.deleteAll();
        merchantRepository.deleteAll();

        testMerchant = merchantRepository.save(
                Merchant.builder()
                        .name("Acme Corp")
                        .apiKeyHash("test_hash_" + UUID.randomUUID())
                        .webhookUrl("https://acme.example.com/webhook")
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        testCustomer = customerRepository.save(
                Customer.builder()
                        .merchant(testMerchant)
                        .email("alice@example.com")
                        .fullName("Alice Wonderland")
                        .build()
        );

        testPaymentMethod = paymentMethodRepository.save(
                PaymentMethod.builder()
                        .customer(testCustomer)
                        .type(PaymentMethodType.CARD)
                        .token("tok_visa_" + UUID.randomUUID())
                        .maskedNumber("**** **** **** 4242")
                        .expiryMonth(12)
                        .expiryYear(2028)
                        .status(PaymentMethodStatus.ACTIVE)
                        .build()
        );
    }

    private HttpHeaders createHeaders(UUID merchantId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Id", merchantId.toString());
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    @Test
    @DisplayName("Should successfully create payment with valid payload and record idempotency")
    void testCreatePayment_Success() throws Exception {
        String idempotencyKey = "key-" + UUID.randomUUID();
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amountCents(10000L) // $100.00
                .currency("USD")
                .customerId(testCustomer.getId())
                .paymentMethodId(testPaymentMethod.getId())
                .metadata(Map.of("orderId", "ORD-12345"))
                .build();

        HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, createHeaders(testMerchant.getId(), idempotencyKey));
        ResponseEntity<String> response = restTemplate.exchange("/v1/payments", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("success").asBoolean()).isTrue();

        JsonNode data = root.get("data");
        UUID paymentId = UUID.fromString(data.get("id").asText());
        assertThat(data.get("amountCents").asLong()).isEqualTo(10000L);
        assertThat(data.get("currency").asText()).isEqualTo("USD");
        assertThat(data.get("status").asText()).isEqualTo(PaymentStatus.CREATED.name());
        assertThat(data.get("idempotencyKey").asText()).isEqualTo(idempotencyKey);

        // Verify PostgreSQL database state
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getAmountCents()).isEqualTo(10000L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.getMetadata().get("orderId")).isEqualTo("ORD-12345");

        // Verify Idempotency Record in DB
        assertThat(idempotencyRecordRepository.findByMerchantIdAndKey(testMerchant.getId(), idempotencyKey)).isPresent();
    }

    @Test
    @DisplayName("Atomic payment creation + outbox record insertion: Should insert outbox event in same transaction")
    void testCreatePayment_AtomicallyInsertsOutboxRecord() throws Exception {
        String idempotencyKey = "outbox-key-" + UUID.randomUUID();
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amountCents(15000L)
                .currency("USD")
                .customerId(testCustomer.getId())
                .paymentMethodId(testPaymentMethod.getId())
                .metadata(Map.of("orderId", "ORD-OUTBOX-1"))
                .build();

        HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, createHeaders(testMerchant.getId(), idempotencyKey));
        ResponseEntity<String> response = restTemplate.exchange("/v1/payments", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("success").asBoolean()).isTrue();
        String paymentId = root.get("data").get("id").asText();

        List<PaymentEventOutbox> outboxRecords = paymentEventOutboxRepository.findAll();
        assertThat(outboxRecords).hasSize(1);

        PaymentEventOutbox outboxRecord = outboxRecords.getFirst();
        assertThat(outboxRecord.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(outboxRecord.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(outboxRecord.getAggregateId()).isEqualTo(paymentId);
        assertThat(outboxRecord.getEventType()).isEqualTo("PaymentInitiatedEvent");
        assertThat(outboxRecord.getHeaders()).containsEntry("merchantId", testMerchant.getId().toString());
        assertThat(outboxRecord.getPayload()).isNotNull();
        assertThat(outboxRecord.getPayload()).contains(paymentId);
        assertThat(outboxRecord.getPayload()).contains("PaymentInitiatedEvent");
    }

    @Test
    @DisplayName("Idempotent replay: Subsequent requests with identical key return identical cached payment without duplicate DB insert")
    void testCreatePayment_IdempotentReplay() throws Exception {
        String idempotencyKey = "key-" + UUID.randomUUID();
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amountCents(5000L)
                .currency("USD")
                .customerId(testCustomer.getId())
                .paymentMethodId(testPaymentMethod.getId())
                .build();

        HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, createHeaders(testMerchant.getId(), idempotencyKey));

        // First call
        ResponseEntity<String> response1 = restTemplate.exchange("/v1/payments", HttpMethod.POST, entity, String.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode data1 = objectMapper.readTree(response1.getBody()).get("data");
        String paymentId1 = data1.get("id").asText();

        // Second call (Exact same key & payload)
        ResponseEntity<String> response2 = restTemplate.exchange("/v1/payments", HttpMethod.POST, entity, String.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode data2 = objectMapper.readTree(response2.getBody()).get("data");
        String paymentId2 = data2.get("id").asText();

        // Must return exact same payment ID
        assertThat(paymentId1).isEqualTo(paymentId2);

        // Verify only 1 payment record exists in PostgreSQL
        assertThat(paymentRepository.count()).isEqualTo(1);

        // Verify replaying the same request does NOT insert a second outbox record
        assertThat(paymentEventOutboxRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Idempotency conflict: Reusing key with different payload returns HTTP 409 Conflict")
    void testCreatePayment_IdempotencyPayloadMismatch_Returns409Conflict() throws Exception {
        String idempotencyKey = "key-" + UUID.randomUUID();

        CreatePaymentRequest initialRequest = CreatePaymentRequest.builder()
                .amountCents(2500L)
                .currency("USD")
                .customerId(testCustomer.getId())
                .build();

        HttpEntity<CreatePaymentRequest> entity1 = new HttpEntity<>(initialRequest, createHeaders(testMerchant.getId(), idempotencyKey));
        ResponseEntity<String> response1 = restTemplate.exchange("/v1/payments", HttpMethod.POST, entity1, String.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Attempt second request with same key but different amount (7500 instead of 2500)
        CreatePaymentRequest conflictingRequest = CreatePaymentRequest.builder()
                .amountCents(7500L)
                .currency("USD")
                .customerId(testCustomer.getId())
                .build();

        HttpEntity<CreatePaymentRequest> entity2 = new HttpEntity<>(conflictingRequest, createHeaders(testMerchant.getId(), idempotencyKey));
        ResponseEntity<String> response2 = restTemplate.exchange("/v1/payments", HttpMethod.POST, entity2, String.class);

        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode errorNode = objectMapper.readTree(response2.getBody());
        assertThat(errorNode.get("error").asText()).isEqualTo("Conflict");
        assertThat(errorNode.get("message").asText()).contains("already used with a different request payload");
    }

    @Test
    @DisplayName("Cross-tenant isolation: Charging customer of another merchant returns HTTP 404 Not Found")
    void testCreatePayment_CustomerBelongingToAnotherMerchant_Returns404NotFound() {
        Merchant anotherMerchant = merchantRepository.save(
                Merchant.builder()
                        .name("Other Merchant")
                        .apiKeyHash("other_hash_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amountCents(1000L)
                .currency("USD")
                .customerId(testCustomer.getId()) // Customer belongs to testMerchant, not anotherMerchant
                .build();

        HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, createHeaders(anotherMerchant.getId(), "key-" + UUID.randomUUID()));
        ResponseEntity<String> response = restTemplate.exchange("/v1/payments", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Validation failure: Negative or zero amount returns HTTP 400 Bad Request")
    void testCreatePayment_InvalidAmount_Returns400BadRequest() {
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amountCents(-500L) // Invalid
                .currency("USD")
                .customerId(testCustomer.getId())
                .build();

        HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, createHeaders(testMerchant.getId(), "key-" + UUID.randomUUID()));
        ResponseEntity<String> response = restTemplate.exchange("/v1/payments", HttpMethod.POST, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /v1/payments/{id} should retrieve payment by ID")
    void testGetPayment_Success() throws Exception {
        String idempotencyKey = "key-" + UUID.randomUUID();
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amountCents(3300L)
                .currency("USD")
                .customerId(testCustomer.getId())
                .build();

        HttpEntity<CreatePaymentRequest> createEntity = new HttpEntity<>(request, createHeaders(testMerchant.getId(), idempotencyKey));
        ResponseEntity<String> createResponse = restTemplate.exchange("/v1/payments", HttpMethod.POST, createEntity, String.class);
        String paymentId = objectMapper.readTree(createResponse.getBody()).get("data").get("id").asText();

        // Fetch via GET
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.set("X-Merchant-Id", testMerchant.getId().toString());
        HttpEntity<Void> getEntity = new HttpEntity<>(getHeaders);

        ResponseEntity<String> getResponse = restTemplate.exchange("/v1/payments/" + paymentId, HttpMethod.GET, getEntity, String.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode getData = objectMapper.readTree(getResponse.getBody()).get("data");
        assertThat(getData.get("id").asText()).isEqualTo(paymentId);
        assertThat(getData.get("amountCents").asLong()).isEqualTo(3300L);
    }

    @Test
    @DisplayName("Concurrency: 10 parallel requests with identical idempotency key create exactly 1 payment in PostgreSQL")
    void testConcurrentIdempotentRequests() {
        String idempotencyKey = "key-concurrent-" + UUID.randomUUID();
        CreatePaymentRequest request = CreatePaymentRequest.builder()
                .amountCents(8800L)
                .currency("USD")
                .customerId(testCustomer.getId())
                .build();

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<CompletableFuture<ResponseEntity<String>>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, createHeaders(testMerchant.getId(), idempotencyKey));
                return restTemplate.exchange("/v1/payments", HttpMethod.POST, entity, String.class);
            }, executor));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Verify responses
        List<String> paymentIds = new ArrayList<>();
        int createdCount = 0;
        int conflictCount = 0;

        for (CompletableFuture<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> response = future.join();
            if (response.getStatusCode() == HttpStatus.CREATED) {
                createdCount++;
                try {
                    String paymentId = objectMapper.readTree(response.getBody()).get("data").get("id").asText();
                    paymentIds.add(paymentId);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else if (response.getStatusCode() == HttpStatus.CONFLICT) {
                conflictCount++;
            }
        }

        // Every request must either get 201 Created (success/replay) or 409 Conflict (in-flight collision)
        assertThat(createdCount + conflictCount).isEqualTo(threadCount);
        assertThat(createdCount).isGreaterThanOrEqualTo(1);

        // If multiple requests got 201 Created, they must all reference the exact same payment ID
        if (!paymentIds.isEmpty()) {
            String firstId = paymentIds.getFirst();
            assertThat(paymentIds).allMatch(id -> id.equals(firstId));
        }

        // Exact DB count in PostgreSQL must be strictly 1
        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(paymentEventOutboxRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /v1/payments should retrieve paginated payments scoped to merchant")
    void testListPayments_PaginatedAndScoped() throws Exception {
        // Create 3 payments for testMerchant
        for (int i = 1; i <= 3; i++) {
            CreatePaymentRequest req = CreatePaymentRequest.builder()
                    .amountCents((long) (i * 1000))
                    .currency("USD")
                    .customerId(testCustomer.getId())
                    .build();
            restTemplate.exchange("/v1/payments", HttpMethod.POST,
                    new HttpEntity<>(req, createHeaders(testMerchant.getId(), "list-key-" + i)), String.class);
        }

        // Query GET /v1/payments with page=0, size=2
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Merchant-Id", testMerchant.getId().toString());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange("/v1/payments?page=0&size=2", HttpMethod.GET, entity, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.get("totalElements").asInt()).isEqualTo(3);
        assertThat(data.get("content").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("GET /v1/payments cross-tenant isolation: Merchant B cannot see Merchant A's payments")
    void testListPayments_CrossTenantIsolation() throws Exception {
        // Create payment for testMerchant
        CreatePaymentRequest req = CreatePaymentRequest.builder()
                .amountCents(5000L)
                .currency("USD")
                .customerId(testCustomer.getId())
                .build();
        restTemplate.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(req, createHeaders(testMerchant.getId(), "iso-key-1")), String.class);

        // Create Merchant B
        Merchant merchantB = merchantRepository.save(
                Merchant.builder()
                        .name("Merchant B")
                        .apiKeyHash("hash_b_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        // Query with Merchant B's header
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Merchant-Id", merchantB.getId().toString());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange("/v1/payments", HttpMethod.GET, entity, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.get("totalElements").asInt()).isEqualTo(0);
        assertThat(data.get("content").isEmpty()).isTrue();
    }
}
