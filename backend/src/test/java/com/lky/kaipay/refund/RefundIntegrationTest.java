package com.lky.kaipay.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.ledger.domain.Account;
import com.lky.kaipay.ledger.domain.AccountType;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.domain.JournalSourceType;
import com.lky.kaipay.ledger.repository.AccountRepository;
import com.lky.kaipay.ledger.repository.JournalRepository;
import com.lky.kaipay.ledger.repository.LedgerEntryRepository;
import com.lky.kaipay.ledger.service.AccountProvisioningService;
import com.lky.kaipay.ledger.service.LedgerService;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.refund.api.dto.CreateRefundRequest;
import com.lky.kaipay.refund.domain.Refund;
import com.lky.kaipay.refund.domain.RefundStatus;
import com.lky.kaipay.refund.repository.RefundRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Refund Endpoints & Ledger Integration Tests")
class RefundIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private RefundRepository refundRepository;

    @Autowired
    private PaymentEventOutboxRepository paymentEventOutboxRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private ObjectMapper objectMapper;

    private Merchant testMerchant;
    private Customer testCustomer;

    @BeforeEach
    void setUp() {
        cleanup();

        testMerchant = merchantRepository.save(
                Merchant.builder()
                        .name("Refund Integration Merchant")
                        .apiKeyHash("hash_ref_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        testCustomer = customerRepository.save(
                Customer.builder()
                        .merchant(testMerchant)
                        .email("refund_integ_" + UUID.randomUUID() + "@example.com")
                        .fullName("Refund Customer")
                        .build()
        );
    }

    @AfterEach
    void tearDown() {
        cleanup();
        if (testCustomer != null && testCustomer.getId() != null) {
            customerRepository.deleteById(testCustomer.getId());
        }
        if (testMerchant != null && testMerchant.getId() != null) {
            merchantRepository.deleteById(testMerchant.getId());
        }
    }

    private void cleanup() {
        refundRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        journalRepository.deleteAll();
        accountRepository.deleteAll();
        paymentEventOutboxRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    private HttpHeaders createHeaders(UUID merchantId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Id", merchantId.toString());
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private Payment createCapturedPayment(long amountCents) {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(amountCents)
                        .currency("USD")
                        .status(PaymentStatus.CAPTURED)
                        .gatewayReference("AUTH-REF-12345")
                        .idempotencyKey("pay_idem_" + UUID.randomUUID())
                        .build()
        );

        // Record initial capture journal
        ledgerService.recordPaymentCapture(payment, UUID.randomUUID());
        return payment;
    }

    @Test
    @DisplayName("Full refund: Creates refund record, transitions payment to REFUNDED, saves outbox event, and posts reversing journal")
    void testCreateRefund_FullRefund_Success() throws Exception {
        Payment payment = createCapturedPayment(10000L); // $100.00
        paymentEventOutboxRepository.deleteAll(); // clear capture outbox to verify refund outbox specifically

        String idempotencyKey = "ref_idem_" + UUID.randomUUID();
        CreateRefundRequest request = CreateRefundRequest.builder()
                .amountCents(10000L)
                .reason("Customer dissatisfaction")
                .build();

        HttpEntity<CreateRefundRequest> entity = new HttpEntity<>(request, createHeaders(testMerchant.getId(), idempotencyKey));
        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("success").asBoolean()).isTrue();
        JsonNode data = root.get("data");
        UUID refundId = UUID.fromString(data.get("id").asText());
        assertThat(data.get("amountCents").asLong()).isEqualTo(10000L);
        assertThat(data.get("status").asText()).isEqualTo(RefundStatus.COMPLETED.name());
        assertThat(data.get("reason").asText()).isEqualTo("Customer dissatisfaction");

        // 1. Verify Refund in PostgreSQL
        Refund refund = refundRepository.findById(refundId).orElseThrow();
        assertThat(refund.getAmountCents()).isEqualTo(10000L);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getReason()).isEqualTo("Customer dissatisfaction");

        // 2. Verify Payment transitioned to REFUNDED
        Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        // 3. Verify Outbox record for PaymentRefundedEvent
        List<PaymentEventOutbox> outboxList = paymentEventOutboxRepository.findAll();
        assertThat(outboxList).hasSize(1);
        PaymentEventOutbox outbox = outboxList.getFirst();
        assertThat(outbox.getAggregateType()).isEqualTo("REFUND");
        assertThat(outbox.getAggregateId()).isEqualTo(refundId.toString());
        assertThat(outbox.getEventType()).isEqualTo("PaymentRefundedEvent");
        assertThat(outbox.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(outbox.getPayload()).contains(refundId.toString());
        assertThat(outbox.getPayload()).contains(payment.getId().toString());

        // 4. Verify Ledger Journal
        List<Journal> refundJournals = journalRepository.findAll().stream()
                .filter(j -> j.getSourceType() == JournalSourceType.PAYMENT_REFUND)
                .toList();
        assertThat(refundJournals).hasSize(1);
        Journal journal = refundJournals.getFirst();
        assertThat(journal.getSourceId()).isEqualTo(refundId.toString());
        assertThat(ledgerEntryRepository.findByJournalId(journal.getId())).hasSize(3);

        // 5. Verify Account Balances
        Account receivable = accountRepository.findByAccountNumber(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC).orElseThrow();
        Account payable = accountRepository.findByMerchantIdAndAccountType(testMerchant.getId(), AccountType.LIABILITY).orElseThrow();
        Account revenue = accountRepository.findByAccountNumber(AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC).orElseThrow();

        long receivableBalance = ledgerService.getAccountBalance(receivable.getId());
        long payableBalance = ledgerService.getAccountBalance(payable.getId());
        long revenueBalance = ledgerService.getAccountBalance(revenue.getId());

        // Full refund:
        // Capture: Receivable -10000, Payable +9680, Revenue +320
        // Refund (10000): fee refunded = round(10000 * 0.029) = 290; merchant net refund = 9710
        // Receivable balance = -10000 + 10000 = 0
        // Payable balance = +9680 - 9710 = -30
        // Revenue balance = +320 - 290 = +30
        // Global balance = 0
        assertThat(receivableBalance).isEqualTo(0L);
        assertThat(receivableBalance + payableBalance + revenueBalance).isEqualTo(0L);
    }

    @Test
    @DisplayName("Partial refunds: Transitions to PARTIALLY_REFUNDED then to REFUNDED upon full amount")
    void testCreateRefund_PartialRefunds_TransitionToPartiallyRefundedThenRefunded() throws Exception {
        Payment payment = createCapturedPayment(10000L); // $100.00

        // 1. First Partial Refund: $40.00 (4000 cents)
        CreateRefundRequest req1 = CreateRefundRequest.builder()
                .amountCents(4000L)
                .reason("Partial return")
                .build();
        ResponseEntity<String> resp1 = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(req1, createHeaders(testMerchant.getId(), "part-ref-1")),
                String.class
        );
        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Payment paymentAfterFirst = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(paymentAfterFirst.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        // 2. Second Partial Refund: $60.00 (6000 cents) - completes total $100.00
        CreateRefundRequest req2 = CreateRefundRequest.builder()
                .amountCents(6000L)
                .reason("Remainder return")
                .build();
        ResponseEntity<String> resp2 = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(req2, createHeaders(testMerchant.getId(), "part-ref-2")),
                String.class
        );
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Payment paymentAfterSecond = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(paymentAfterSecond.getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        // Verify total refunds in DB
        List<Refund> refunds = refundRepository.findByPaymentId(payment.getId());
        assertThat(refunds).hasSize(2);
        assertThat(refundRepository.sumRefundedAmountByPaymentId(payment.getId())).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Validation: Refund amount exceeding remaining refundable balance returns HTTP 400 Bad Request")
    void testCreateRefund_ExceedsMaxRefundable_Returns400BadRequest() {
        Payment payment = createCapturedPayment(10000L); // $100.00

        // Refund $40.00
        CreateRefundRequest req1 = CreateRefundRequest.builder()
                .amountCents(4000L)
                .reason("Partial return")
                .build();
        restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(req1, createHeaders(testMerchant.getId(), "ref-valid-1")),
                String.class
        );

        // Attempt to refund $70.00 when only $60.00 is left
        CreateRefundRequest req2 = CreateRefundRequest.builder()
                .amountCents(7000L)
                .reason("Too much")
                .build();
        ResponseEntity<String> resp2 = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(req2, createHeaders(testMerchant.getId(), "ref-exceed-2")),
                String.class
        );

        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Invalid status: Attempting to refund payment in AUTHORIZED or CREATED status returns HTTP 400")
    void testCreateRefund_PaymentNotCaptured_Returns400BadRequest() {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(5000L)
                        .currency("USD")
                        .status(PaymentStatus.AUTHORIZED)
                        .idempotencyKey("pay_idem_" + UUID.randomUUID())
                        .build()
        );

        CreateRefundRequest request = CreateRefundRequest.builder()
                .amountCents(2000L)
                .reason("Premature refund")
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(testMerchant.getId(), "ref-unauth")),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Idempotent replay: Subsequent requests with same idempotency key return existing refund without duplication")
    void testCreateRefund_IdempotentReplay() throws Exception {
        Payment payment = createCapturedPayment(10000L);
        paymentEventOutboxRepository.deleteAll();

        String idempotencyKey = "ref_idem_replay_" + UUID.randomUUID();
        CreateRefundRequest request = CreateRefundRequest.builder()
                .amountCents(3000L)
                .reason("Damaged item")
                .build();

        HttpEntity<CreateRefundRequest> entity = new HttpEntity<>(request, createHeaders(testMerchant.getId(), idempotencyKey));

        // First call
        ResponseEntity<String> response1 = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST,
                entity,
                String.class
        );
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String refundId1 = objectMapper.readTree(response1.getBody()).get("data").get("id").asText();

        // Second call (same idempotency key)
        ResponseEntity<String> response2 = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST,
                entity,
                String.class
        );
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String refundId2 = objectMapper.readTree(response2.getBody()).get("data").get("id").asText();

        assertThat(refundId1).isEqualTo(refundId2);
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(paymentEventOutboxRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /v1/payments/{paymentId}/refunds retrieves all refunds for the payment")
    void testGetPaymentRefunds_Success() throws Exception {
        Payment payment = createCapturedPayment(10000L);

        // Create 2 partial refunds
        CreateRefundRequest req1 = CreateRefundRequest.builder().amountCents(2000L).reason("Reason 1").build();
        CreateRefundRequest req2 = CreateRefundRequest.builder().amountCents(3000L).reason("Reason 2").build();

        restTemplate.exchange("/v1/payments/" + payment.getId() + "/refunds", HttpMethod.POST,
                new HttpEntity<>(req1, createHeaders(testMerchant.getId(), "list-ref-1")), String.class);
        restTemplate.exchange("/v1/payments/" + payment.getId() + "/refunds", HttpMethod.POST,
                new HttpEntity<>(req2, createHeaders(testMerchant.getId(), "list-ref-2")), String.class);

        // Query GET
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Merchant-Id", testMerchant.getId().toString());
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.GET,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Cross-tenant isolation: Merchant B cannot refund or view refunds of Merchant A's payment")
    void testRefund_CrossTenantIsolation_Returns404() {
        Payment payment = createCapturedPayment(10000L);

        Merchant merchantB = merchantRepository.save(
                Merchant.builder()
                        .name("Merchant B")
                        .apiKeyHash("hash_b_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        CreateRefundRequest request = CreateRefundRequest.builder()
                .amountCents(1000L)
                .build();

        // Merchant B tries to create refund on Merchant A's payment -> 404
        ResponseEntity<String> postResponse = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(merchantB.getId(), "x-tenant-ref")),
                String.class
        );
        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Merchant B tries to list refunds on Merchant A's payment -> 404
        HttpHeaders getHeaders = new HttpHeaders();
        getHeaders.set("X-Merchant-Id", merchantB.getId().toString());
        ResponseEntity<String> getResponse = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.GET,
                new HttpEntity<>(getHeaders),
                String.class
        );
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Validation: Negative refund amount returns HTTP 400 Bad Request")
    void testCreateRefund_InvalidAmount_Returns400() {
        Payment payment = createCapturedPayment(10000L);

        CreateRefundRequest request = CreateRefundRequest.builder()
                .amountCents(-500L)
                .build();

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(testMerchant.getId(), "neg-amount")),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
