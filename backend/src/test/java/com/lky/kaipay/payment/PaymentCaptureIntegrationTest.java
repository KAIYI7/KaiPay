package com.lky.kaipay.payment;

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

@DisplayName("Payment Capture Integration Tests")
class PaymentCaptureIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventOutboxRepository paymentEventOutboxRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RefundRepository refundRepository;

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
                        .name("Capture Test Merchant")
                        .apiKeyHash("hash_cap_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        testCustomer = customerRepository.save(
                Customer.builder()
                        .merchant(testMerchant)
                        .email("capture_cust_" + UUID.randomUUID() + "@example.com")
                        .fullName("Capture Customer")
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

    @Test
    @DisplayName("Should successfully capture an AUTHORIZED payment, persist outbox event and post ledger journal")
    void testCapturePayment_Success() throws Exception {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(10000L) // $100.00
                        .currency("USD")
                        .status(PaymentStatus.AUTHORIZED)
                        .gatewayReference("AUTH-CAP-12345")
                        .idempotencyKey("pay_idem_" + UUID.randomUUID())
                        .build()
        );

        String idempotencyKey = "cap_idem_" + UUID.randomUUID();
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(testMerchant.getId(), idempotencyKey));

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/capture",
                HttpMethod.POST,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("success").asBoolean()).isTrue();
        JsonNode data = root.get("data");
        assertThat(data.get("id").asText()).isEqualTo(payment.getId().toString());
        assertThat(data.get("status").asText()).isEqualTo(PaymentStatus.CAPTURED.name());
        assertThat(data.get("amountCents").asLong()).isEqualTo(10000L);

        // 1. Verify payment status in DB
        Payment updatedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(updatedPayment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);

        // 2. Verify Outbox record
        List<PaymentEventOutbox> outboxEvents = paymentEventOutboxRepository.findAll();
        assertThat(outboxEvents).hasSize(1);
        PaymentEventOutbox outbox = outboxEvents.getFirst();
        assertThat(outbox.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(outbox.getAggregateId()).isEqualTo(payment.getId().toString());
        assertThat(outbox.getEventType()).isEqualTo("PaymentCapturedEvent");
        assertThat(outbox.getStatus()).isEqualTo(PaymentEventOutboxStatus.PENDING);
        assertThat(outbox.getPayload()).contains(payment.getId().toString());
        assertThat(outbox.getPayload()).contains("PaymentCapturedEvent");

        // 3. Verify Ledger Journal
        List<Journal> journals = journalRepository.findAll();
        assertThat(journals).hasSize(1);
        Journal journal = journals.getFirst();
        assertThat(journal.getSourceType()).isEqualTo(JournalSourceType.PAYMENT_CAPTURE);
        assertThat(journal.getSourceId()).isEqualTo(payment.getId().toString());
        assertThat(ledgerEntryRepository.findByJournalId(journal.getId())).hasSize(3);

        // 4. Verify Account Balances
        Account receivable = accountRepository.findByAccountNumber(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC).orElseThrow();
        Account payable = accountRepository.findByMerchantIdAndAccountType(testMerchant.getId(), AccountType.LIABILITY).orElseThrow();
        Account revenue = accountRepository.findByAccountNumber(AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC).orElseThrow();

        long receivableBalance = ledgerService.getAccountBalance(receivable.getId());
        long payableBalance = ledgerService.getAccountBalance(payable.getId());
        long revenueBalance = ledgerService.getAccountBalance(revenue.getId());

        // Fee: round(10000 * 0.029) + 30 = 320 cents ($3.20)
        // Net Merchant: 10000 - 320 = 9680 cents ($96.80)
        assertThat(receivableBalance).isEqualTo(-10000L);
        assertThat(payableBalance).isEqualTo(9680L);
        assertThat(revenueBalance).isEqualTo(320L);
        assertThat(receivableBalance + payableBalance + revenueBalance).isEqualTo(0L);
    }

    @Test
    @DisplayName("Idempotent capture: Capturing already CAPTURED payment returns 200 OK without duplicate ledger/outbox")
    void testCapturePayment_IdempotentReplay() throws Exception {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(10000L)
                        .currency("USD")
                        .status(PaymentStatus.AUTHORIZED)
                        .idempotencyKey("pay_idem_" + UUID.randomUUID())
                        .build()
        );

        String idempotencyKey = "cap_idem_" + UUID.randomUUID();
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(testMerchant.getId(), idempotencyKey));

        // First capture call
        ResponseEntity<String> response1 = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/capture",
                HttpMethod.POST,
                entity,
                String.class
        );
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentEventOutboxRepository.count()).isEqualTo(1);
        assertThat(journalRepository.count()).isEqualTo(1);

        // Second capture call (idempotent replay)
        ResponseEntity<String> response2 = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/capture",
                HttpMethod.POST,
                entity,
                String.class
        );
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data2 = objectMapper.readTree(response2.getBody()).get("data");
        assertThat(data2.get("status").asText()).isEqualTo(PaymentStatus.CAPTURED.name());

        // Outbox and Journal counts must remain 1
        assertThat(paymentEventOutboxRepository.count()).isEqualTo(1);
        assertThat(journalRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Invalid status: Capturing payment in CREATED status returns HTTP 400 Bad Request")
    void testCapturePayment_InvalidStatus_Returns400BadRequest() {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(testMerchant)
                        .customer(testCustomer)
                        .amountCents(5000L)
                        .currency("USD")
                        .status(PaymentStatus.CREATED)
                        .idempotencyKey("pay_idem_" + UUID.randomUUID())
                        .build()
        );

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(testMerchant.getId(), "cap_idem_" + UUID.randomUUID()));
        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/capture",
                HttpMethod.POST,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Not found: Capturing non-existent payment returns HTTP 404 Not Found")
    void testCapturePayment_NotFound_Returns404() {
        UUID nonExistentPaymentId = UUID.randomUUID();
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(testMerchant.getId(), "cap_idem_" + UUID.randomUUID()));

        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/payments/" + nonExistentPaymentId + "/capture",
                HttpMethod.POST,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Cross-tenant isolation: Merchant B cannot capture Merchant A's authorized payment")
    void testCapturePayment_CrossTenantIsolation_Returns404() {
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

        Merchant merchantB = merchantRepository.save(
                Merchant.builder()
                        .name("Merchant B")
                        .apiKeyHash("hash_b_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(merchantB.getId(), "cap_idem_" + UUID.randomUUID()));
        ResponseEntity<String> response = restTemplate.exchange(
                "/v1/payments/" + payment.getId() + "/capture",
                HttpMethod.POST,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
