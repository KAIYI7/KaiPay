package com.lky.kaipay.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.lky.kaipay.refund.api.dto.RefundResponse;
import com.lky.kaipay.refund.domain.Refund;
import com.lky.kaipay.refund.domain.RefundStatus;
import com.lky.kaipay.refund.repository.RefundRepository;
import com.lky.kaipay.refund.service.RefundService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

    @Autowired
    private RefundService refundService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        HttpHeaders headers = security().merchantHeaders(merchantId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Id", merchantId.toString());
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private Payment createCapturedPayment(long amountCents) {
        return createCapturedPayment(testMerchant, testCustomer, amountCents);
    }

    private Payment createCapturedPayment(Merchant merchant, Customer customer, long amountCents) {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(merchant)
                        .customer(customer)
                        .amountCents(amountCents)
                        .currency("MYR")
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
        Payment payment = createCapturedPayment(10000L); // RM100.00
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
        Payment payment = createCapturedPayment(10000L); // RM100.00

        // 1. First Partial Refund: RM40.00 (4000 cents)
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

        // 2. Second Partial Refund: RM60.00 (6000 cents) - completes total RM100.00
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
        Payment payment = createCapturedPayment(10000L); // RM100.00

        // Refund RM40.00
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

        // Attempt to refund RM70.00 when only RM60.00 is left
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
                        .currency("MYR")
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
        EffectSnapshot beforeReplay = effectSnapshot();

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
        assertReplayResponse(objectMapper.readTree(response1.getBody()).get("data"),
                objectMapper.readTree(response2.getBody()).get("data"));
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(paymentEventOutboxRepository.count()).isEqualTo(1);
        assertThat(effectSnapshot()).isEqualTo(beforeReplay);
    }

    @Test
    @DisplayName("Exact full-refund replay returns original 201 response despite REFUNDED payment state")
    void testCreateRefund_FullRefundReplay_ReturnsOriginalWithoutSideEffects() throws Exception {
        Payment payment = createCapturedPayment(10000L);
        String key = "full_replay_" + UUID.randomUUID();
        ResponseEntity<String> first = postRefund(payment, key, 10000L, "Return");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertSingleRefundEffects();
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        EffectSnapshot before = effectSnapshot();
        ResponseEntity<String> replay = postRefund(payment, key, 10000L, "Return");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertReplayResponse(objectMapper.readTree(first.getBody()).get("data"),
                objectMapper.readTree(replay.getBody()).get("data"));
        assertThat(effectSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest(name = "reason equality: {0} versus {1}, replay={2}")
    @MethodSource("reasonCases")
    void testCreateRefund_ReasonUsesExactNullSafeEquality(String originalReason, String nextReason, boolean replay) throws Exception {
        Payment payment = createCapturedPayment(10000L);
        String key = "reason_" + UUID.randomUUID();
        ResponseEntity<String> first = postRefund(payment, key, 3000L, originalReason);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertSingleRefundEffects();
        EffectSnapshot before = effectSnapshot();
        ResponseEntity<String> next = postRefund(payment, key, 3000L, nextReason);
        assertThat(next.getStatusCode()).isEqualTo(replay ? HttpStatus.CREATED : HttpStatus.CONFLICT);
        if (replay) {
            assertReplayResponse(objectMapper.readTree(first.getBody()).get("data"),
                    objectMapper.readTree(next.getBody()).get("data"));
        }
        assertThat(effectSnapshot()).isEqualTo(before);
    }

    private static Stream<Arguments> reasonCases() {
        return Stream.of(Arguments.of(null, null, true), Arguments.of("", "", true),
                Arguments.of("  ", "  ", true), Arguments.of(null, "", false),
                Arguments.of(null, " ", false), Arguments.of("", " ", false),
                Arguments.of(" Return ", "Return", false));
    }

    @Test
    void testCreateRefund_OmittedAndExplicitNullReason_AreSameRequest() throws Exception {
        Payment payment = createCapturedPayment(10000L);
        String key = "null_reason_" + UUID.randomUUID();
        HttpHeaders headers = createHeaders(testMerchant.getId(), key);
        ResponseEntity<String> first = restTemplate.exchange("/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST, new HttpEntity<>("{\"amountCents\":3000}", headers), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertSingleRefundEffects();
        EffectSnapshot before = effectSnapshot();
        ResponseEntity<String> replay = restTemplate.exchange("/v1/payments/" + payment.getId() + "/refunds",
                HttpMethod.POST, new HttpEntity<>("{\"amountCents\":3000,\"reason\":null}", headers), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertReplayResponse(objectMapper.readTree(first.getBody()).get("data"),
                objectMapper.readTree(replay.getBody()).get("data"));
        assertThat(effectSnapshot()).isEqualTo(before);
    }

    @Test
    void testCreateRefund_MissingIdempotencyKey_Returns400WithoutSideEffects() {
        Payment payment = createCapturedPayment(10000L);
        EffectSnapshot before = effectSnapshot();
        ResponseEntity<String> response = postRefund(payment, null, 3000L, "Return");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Missing required header: Idempotency-Key");
        assertThat(effectSnapshot()).isEqualTo(before);
    }

    @Test
    void testCreateRefund_SameKeyForDifferentMerchants_IsIndependent() throws Exception {
        Merchant otherMerchant = merchantRepository.save(Merchant.builder().name("Independent Merchant")
                .apiKeyHash("hash_independent_" + UUID.randomUUID()).status(MerchantStatus.ACTIVE).build());
        Customer otherCustomer = customerRepository.save(Customer.builder().merchant(otherMerchant)
                .email(UUID.randomUUID() + "@example.com").fullName("Independent Customer").build());
        try {
            Payment firstPayment = createCapturedPayment(10000L);
            Payment secondPayment = createCapturedPayment(otherMerchant, otherCustomer, 10000L);
            String key = "merchant_scoped_" + UUID.randomUUID();
            ResponseEntity<String> first = postRefund(firstPayment, key, 3000L, "First");
            ResponseEntity<String> second = postRefund(secondPayment, key, 4000L, "Second");
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(objectMapper.readTree(first.getBody()).get("data").get("id"))
                    .isNotEqualTo(objectMapper.readTree(second.getBody()).get("data").get("id"));
            assertThat(refundRepository.findByMerchantIdAndIdempotencyKey(testMerchant.getId(), key).orElseThrow().getAmountCents())
                    .isEqualTo(3000L);
            assertThat(refundRepository.findByMerchantIdAndIdempotencyKey(otherMerchant.getId(), key).orElseThrow().getAmountCents())
                    .isEqualTo(4000L);
            assertThat(paymentEventOutboxRepository.count()).isEqualTo(2);
            assertThat(journalRepository.findAll().stream().filter(j -> j.getSourceType() == JournalSourceType.PAYMENT_REFUND))
                    .hasSize(2);
        } finally {
            cleanup();
            customerRepository.deleteById(otherCustomer.getId());
            merchantRepository.deleteById(otherMerchant.getId());
        }
    }

    @Test
    @DisplayName("Concurrent exact full refund waits for payment lock then replays original refund with HTTP 201")
    void testCreateRefund_ConcurrentExactFullRefund_ReplaysAfterLock() throws Exception {
        Payment payment = createCapturedPayment(10000L);
        RefundRace race = runRefundRace(payment, payment, 10000L, 10000L, "Return", "Return");
        assertThat(race.loser().getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertReplayResponse(objectMapper.readTree(objectMapper.writeValueAsString(race.winner())),
                objectMapper.readTree(race.loser().getBody()).get("data"));
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("Concurrent conflicting refund waits for same payment then returns 409 without mutation")
    void testCreateRefund_ConcurrentConflictingSamePayment_Returns409AfterLock() throws Exception {
        Payment payment = createCapturedPayment(10000L);
        RefundRace race = runRefundRace(payment, payment, 10000L, 3000L, "Return", "Different");
        assertThat(race.loser().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(race.loser().getBody()).contains("idempotency key");
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("Concurrent conflicting different payment reaches unique constraint and rolls back to HTTP 409")
    void testCreateRefund_ConcurrentConflictingDifferentPayment_Returns409AndRollsBack() throws Exception {
        Payment first = createCapturedPayment(10000L);
        Payment other = createCapturedPayment(10000L);
        RefundRace race = runRefundRace(first, other, 3000L, 3000L, "Return", "Return");
        assertThat(race.loser().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(race.loser().getBody()).contains("Concurrent request conflict");
        assertThat(paymentRepository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(paymentRepository.findById(other.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(refundRepository.findByPaymentId(other.getId())).isEmpty();
    }

    private RefundRace runRefundRace(Payment winnerPayment, Payment loserPayment, long winnerAmount,
                                    long loserAmount, String winnerReason, String loserReason) throws Exception {
        String key = "race_" + UUID.randomUUID();
        CountDownLatch winnerFlushed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        AtomicInteger winnerBackendPid = new AtomicInteger();
        AtomicReference<EffectSnapshot> winnerEffects = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RefundResponse> winner = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                RefundResponse response = refundService.createRefund(testMerchant.getId(), winnerPayment.getId(), key,
                        CreateRefundRequest.builder().amountCents(winnerAmount).reason(winnerReason).build());
                entityManager.flush();
                winnerBackendPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                winnerEffects.set(effectSnapshot());
                winnerFlushed.countDown();
                try {
                    if (!allowCommit.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release winner transaction");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted winner transaction", e);
                }
                return response;
            }));
            assertThat(winnerFlushed.await(15, TimeUnit.SECONDS)).as("winner flushed refund before commit").isTrue();
            Future<ResponseEntity<String>> loser = executor.submit(() -> postRefund(loserPayment, key, loserAmount, loserReason));
            // Real PostgreSQL evidence that the HTTP transaction passed its initial lookup and
            // is blocked by this winner, rather than a timing assumption based on thread startup.
            await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND ? = ANY(pg_blocking_pids(pid)))", Boolean.class, winnerBackendPid.get())));
            assertThat(loser.isDone()).as("loser cannot finish while winner holds its database lock").isFalse();
            allowCommit.countDown();
            RefundResponse winnerResponse = winner.get(15, TimeUnit.SECONDS);
            ResponseEntity<String> loserResponse = loser.get(15, TimeUnit.SECONDS);
            assertThat(refundRepository.count()).isEqualTo(1);
            assertThat(refundRepository.sumRefundedAmountByPaymentId(winnerPayment.getId())).isEqualTo(winnerAmount);
            assertThat(paymentEventOutboxRepository.count()).isEqualTo(1);
            assertThat(journalRepository.findAll().stream().filter(j -> j.getSourceType() == JournalSourceType.PAYMENT_REFUND))
                    .hasSize(1);
            assertThat(effectSnapshot()).as("loser creates no payment/refund/outbox/accounting effects")
                    .isEqualTo(winnerEffects.get());
            return new RefundRace(winnerResponse, loserResponse);
        } finally {
            allowCommit.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("refund worker cleanup").isTrue();
            }
        }
    }

    private record RefundRace(RefundResponse winner, ResponseEntity<String> loser) {}

    @Test
    @DisplayName("Idempotency conflict: same merchant key with a different payment returns 409 without side effects")
    void testCreateRefund_SameKeyDifferentPayment_Returns409WithoutSideEffects() {
        Payment original = createCapturedPayment(10000L);
        Payment other = createCapturedPayment(10000L);
        assertRefundConflictLeavesStateUnchanged(original, other, 3000L, "Damaged item");
    }

    @Test
    @DisplayName("Idempotency conflict: same key with a different amount returns 409 without side effects")
    void testCreateRefund_SameKeyDifferentAmount_Returns409WithoutSideEffects() {
        Payment payment = createCapturedPayment(10000L);
        assertRefundConflictLeavesStateUnchanged(payment, payment, 4000L, "Damaged item");
    }

    @Test
    @DisplayName("Idempotency conflict: same key with a different reason returns 409 without side effects")
    void testCreateRefund_SameKeyDifferentReason_Returns409WithoutSideEffects() {
        Payment payment = createCapturedPayment(10000L);
        assertRefundConflictLeavesStateUnchanged(payment, payment, 3000L, "Changed reason");
    }

    private void assertRefundConflictLeavesStateUnchanged(Payment original, Payment target, long amount, String reason) {
        String key = "conflict_" + UUID.randomUUID();
        ResponseEntity<String> first = postRefund(original, key, 3000L, "Damaged item");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Refund persisted = refundRepository.findByMerchantIdAndIdempotencyKey(testMerchant.getId(), key).orElseThrow();
        assertThat(persisted.getAmountCents()).isEqualTo(3000L);
        assertThat(persisted.getReason()).isEqualTo("Damaged item");
        assertThat(persisted.getPayment().getId()).isEqualTo(original.getId());
        EffectSnapshot before = effectSnapshot();

        ResponseEntity<String> conflict = postRefund(target, key, amount, reason);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("idempotency key");
        assertThat(effectSnapshot()).isEqualTo(before);
    }

    private ResponseEntity<String> postRefund(Payment payment, String key, long amount, String reason) {
        CreateRefundRequest request = CreateRefundRequest.builder().amountCents(amount).reason(reason).build();
        return restTemplate.exchange("/v1/payments/" + payment.getId() + "/refunds", HttpMethod.POST,
                new HttpEntity<>(request, createHeaders(payment.getMerchant().getId(), key)), String.class);
    }

    private void assertSingleRefundEffects() {
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(paymentEventOutboxRepository.count()).isEqualTo(1);
        List<Journal> journals = journalRepository.findAll().stream()
                .filter(j -> j.getSourceType() == JournalSourceType.PAYMENT_REFUND).toList();
        assertThat(journals).hasSize(1);
        assertThat(ledgerEntryRepository.findByJournalId(journals.getFirst().getId())).hasSize(3);
    }

    private void assertReplayResponse(JsonNode original, JsonNode replay) {
        ObjectNode originalFields = original.deepCopy();
        ObjectNode replayFields = replay.deepCopy();
        // Existing deferred flushing can leave createdAt absent from the first response.
        // Replay must preserve every business field and return the persisted timestamp.
        originalFields.remove("createdAt");
        replayFields.remove("createdAt");
        assertThat(replayFields).isEqualTo(originalFields);
        Refund persisted = refundRepository.findById(UUID.fromString(replay.get("id").asText())).orElseThrow();
        assertThat(replay.get("createdAt")).isEqualTo(objectMapper.valueToTree(persisted.getCreatedAt()));
        if (original.hasNonNull("createdAt")) {
            assertThat(replay.get("createdAt")).isEqualTo(original.get("createdAt"));
        }
    }

    private OutboxSnapshot outboxSnapshot(PaymentEventOutbox outbox) {
        try {
            // PostgreSQL JSONB may reorder object keys; compare the entire semantic payload.
            return new OutboxSnapshot(outbox.getId(), objectMapper.readTree(outbox.getPayload()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Invalid persisted outbox JSON", e);
        }
    }

    private EffectSnapshot effectSnapshot() {
        return new EffectSnapshot(
                paymentRepository.findAll().stream().map(p -> p.getId() + ":" + p.getStatus()).sorted().toList(),
                refundRepository.findAll().stream().map(r -> r.getId() + ":" + r.getPayment().getId() + ":"
                        + r.getAmountCents() + ":" + r.getReason()).sorted().toList(),
                paymentEventOutboxRepository.findAll().stream().map(this::outboxSnapshot)
                        .sorted(Comparator.comparing(OutboxSnapshot::id)).toList(),
                journalRepository.findAll().stream().map(j -> j.getId() + ":" + j.getSourceId()).sorted().toList(),
                ledgerEntryRepository.findAll().stream().map(e -> e.getId() + ":" + e.getAccount().getId() + ":"
                        + e.getEntryType() + ":" + e.getAmountCents()).sorted().toList(),
                accountRepository.findAll().stream().map(a -> a.getId() + ":" + ledgerService.getAccountBalance(a.getId()))
                        .sorted().toList());
    }

    private record OutboxSnapshot(UUID id, JsonNode payload) {}

    private record EffectSnapshot(List<String> payments, List<String> refunds, List<OutboxSnapshot> outbox,
                                  List<String> journals, List<String> entries, List<String> balances) {}

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
        HttpHeaders headers = security().merchantHeaders(testMerchant.getId());
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
        HttpHeaders getHeaders = security().merchantHeaders(merchantB.getId());
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
