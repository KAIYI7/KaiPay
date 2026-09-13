package com.lky.kaipay.ledger.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.repository.AccountRepository;
import com.lky.kaipay.ledger.repository.JournalRepository;
import com.lky.kaipay.ledger.repository.LedgerEntryRepository;
import com.lky.kaipay.ledger.service.LedgerService;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentRepository;
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
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ledger & Merchant Balance Controller Integration Tests")
class LedgerControllerIntegrationTest extends AbstractPostgresIntegrationTest {

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
    private JournalRepository journalRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private ObjectMapper objectMapper;

    private Merchant merchantA;
    private Merchant merchantB;
    private Customer customerA;

    @BeforeEach
    void setUp() {
        cleanup();

        merchantA = merchantRepository.save(
                Merchant.builder()
                        .name("Alpha Merchant")
                        .apiKeyHash("hash_alpha_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        merchantB = merchantRepository.save(
                Merchant.builder()
                        .name("Beta Merchant")
                        .apiKeyHash("hash_beta_" + UUID.randomUUID())
                        .status(MerchantStatus.ACTIVE)
                        .build()
        );

        customerA = customerRepository.save(
                Customer.builder()
                        .merchant(merchantA)
                        .email("alpha_cust_" + UUID.randomUUID() + "@example.com")
                        .fullName("Customer Alpha")
                        .build()
        );
    }

    @AfterEach
    void tearDown() {
        cleanup();
        if (customerA != null && customerA.getId() != null) {
            customerRepository.deleteById(customerA.getId());
        }
        if (merchantA != null && merchantA.getId() != null) {
            merchantRepository.deleteById(merchantA.getId());
        }
        if (merchantB != null && merchantB.getId() != null) {
            merchantRepository.deleteById(merchantB.getId());
        }
    }

    private void cleanup() {
        refundRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        journalRepository.deleteAll();
        accountRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    private HttpHeaders createHeaders(UUID merchantId) {
        HttpHeaders headers = new HttpHeaders();
        if (merchantId != null) {
            headers.set("X-Merchant-Id", merchantId.toString());
        }
        return headers;
    }

    @Test
    @DisplayName("GET /v1/ledger/balance returns projected balances for merchant after payment and refund")
    void testGetLedgerBalance_Success() throws Exception {
        // 1. Create and capture a payment of $100.00 (10000 cents)
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(merchantA)
                        .customer(customerA)
                        .amountCents(10000L)
                        .currency("USD")
                        .status(PaymentStatus.CAPTURED)
                        .idempotencyKey("pay_key_" + UUID.randomUUID())
                        .build()
        );
        ledgerService.recordPaymentCapture(payment, UUID.randomUUID());

        // 2. Create and record a refund of $30.00 (3000 cents)
        Refund refund = refundRepository.save(
                Refund.builder()
                        .payment(payment)
                        .merchant(merchantA)
                        .amountCents(3000L)
                        .currency("USD")
                        .status(RefundStatus.COMPLETED)
                        .idempotencyKey("ref_key_" + UUID.randomUUID())
                        .build()
        );
        ledgerService.recordPaymentRefund(payment, refund, UUID.randomUUID());

        // Calculations:
        // Capture: Fee = round(10000 * 0.029) + 30 = 320 cents. Net = 9680 cents.
        // Refund: Fee Refund = round(3000 * 0.029) = 87 cents. Net Refund = 2913 cents.
        // Available Balance = 9680 - 2913 = 6767 cents.
        // Total Volume = 10000 cents.
        // Net Fees = 320 - 87 = 233 cents.
        // Total Refunds = 3000 cents.

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(merchantA.getId()));
        ResponseEntity<String> response = restTemplate.exchange("/v1/ledger/balance", HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.get("success").asBoolean()).isTrue();

        JsonNode data = root.get("data");
        assertThat(data.get("merchantId").asText()).isEqualTo(merchantA.getId().toString());
        assertThat(data.get("availableBalanceCents").asLong()).isEqualTo(6767L);
        assertThat(data.get("pendingSettlementCents").asLong()).isEqualTo(0L);
        assertThat(data.get("totalVolumeCents").asLong()).isEqualTo(10000L);
        assertThat(data.get("totalFeesCents").asLong()).isEqualTo(233L);
        assertThat(data.get("totalRefundsCents").asLong()).isEqualTo(3000L);
        assertThat(data.get("currency").asText()).isEqualTo("USD");
    }

    @Test
    @DisplayName("GET /v1/merchants/balance direct route returns identical projection")
    void testGetMerchantsBalance_DirectRouteCompatibility() throws Exception {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(merchantA)
                        .customer(customerA)
                        .amountCents(5000L)
                        .currency("USD")
                        .status(PaymentStatus.CAPTURED)
                        .idempotencyKey("pay_direct_" + UUID.randomUUID())
                        .build()
        );
        ledgerService.recordPaymentCapture(payment, UUID.randomUUID());

        // Fee = round(5000 * 0.029) + 30 = 175 cents. Net = 4825 cents.
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(merchantA.getId()));
        ResponseEntity<String> response = restTemplate.exchange("/v1/merchants/balance", HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.get("merchantId").asText()).isEqualTo(merchantA.getId().toString());
        assertThat(data.get("availableBalanceCents").asLong()).isEqualTo(4825L);
        assertThat(data.get("totalVolumeCents").asLong()).isEqualTo(5000L);
        assertThat(data.get("totalFeesCents").asLong()).isEqualTo(175L);
    }

    @Test
    @DisplayName("GET /v1/ledger/balance returns 404 Not Found for non-existent merchant")
    void testGetLedgerBalance_NotFound() {
        UUID unknownId = UUID.randomUUID();
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(unknownId));
        ResponseEntity<String> response = restTemplate.exchange("/v1/ledger/balance", HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /v1/ledger/balance returns 400 Bad Request when X-Merchant-Id header is missing")
    void testGetLedgerBalance_MissingHeader() {
        HttpEntity<Void> entity = new HttpEntity<>(new HttpHeaders());
        ResponseEntity<String> response = restTemplate.exchange("/v1/ledger/balance", HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /v1/ledger/journals returns paginated journal list")
    void testListJournals_Paginated() throws Exception {
        for (int i = 0; i < 3; i++) {
            Payment payment = paymentRepository.save(
                    Payment.builder()
                            .merchant(merchantA)
                            .customer(customerA)
                            .amountCents((long) (1000 * (i + 1)))
                            .currency("USD")
                            .status(PaymentStatus.CAPTURED)
                            .idempotencyKey("jnl_pay_" + i + "_" + UUID.randomUUID())
                            .build()
            );
            ledgerService.recordPaymentCapture(payment, UUID.randomUUID());
        }

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(merchantA.getId()));
        ResponseEntity<String> response = restTemplate.exchange("/v1/ledger/journals?page=0&size=2", HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.get("totalElements").asInt()).isEqualTo(3);
        assertThat(data.get("content").size()).isEqualTo(2);

        JsonNode firstJournal = data.get("content").get(0);
        assertThat(firstJournal.get("journalNumber").asText()).startsWith("JNL-");
        assertThat(firstJournal.get("totalDebitCents").asLong()).isGreaterThan(0L);
        assertThat(firstJournal.get("totalCreditCents").asLong()).isEqualTo(firstJournal.get("totalDebitCents").asLong());
        assertThat(firstJournal.get("entries").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET /v1/ledger/journals isolates journals per merchant")
    void testListJournals_MerchantIsolation() throws Exception {
        // Merchant A payment
        Payment paymentA = paymentRepository.save(
                Payment.builder()
                        .merchant(merchantA)
                        .customer(customerA)
                        .amountCents(2000L)
                        .currency("USD")
                        .status(PaymentStatus.CAPTURED)
                        .idempotencyKey("pay_a_" + UUID.randomUUID())
                        .build()
        );
        ledgerService.recordPaymentCapture(paymentA, UUID.randomUUID());

        // Merchant B query
        HttpEntity<Void> entityB = new HttpEntity<>(createHeaders(merchantB.getId()));
        ResponseEntity<String> responseB = restTemplate.exchange("/v1/ledger/journals", HttpMethod.GET, entityB, String.class);

        assertThat(responseB.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode dataB = objectMapper.readTree(responseB.getBody()).get("data");
        assertThat(dataB.get("totalElements").asInt()).isEqualTo(0);
        assertThat(dataB.get("content").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("GET /v1/ledger/journals/{id} returns journal by ID")
    void testGetJournalById_Success() throws Exception {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(merchantA)
                        .customer(customerA)
                        .amountCents(10000L)
                        .currency("USD")
                        .status(PaymentStatus.CAPTURED)
                        .idempotencyKey("pay_single_" + UUID.randomUUID())
                        .build()
        );
        Journal createdJournal = ledgerService.recordPaymentCapture(payment, UUID.randomUUID());

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(merchantA.getId()));
        ResponseEntity<String> response = restTemplate.exchange("/v1/ledger/journals/" + createdJournal.getId(), HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).get("data");
        assertThat(data.get("id").asText()).isEqualTo(createdJournal.getId().toString());
        assertThat(data.get("journalNumber").asText()).isEqualTo(createdJournal.getJournalNumber());
        assertThat(data.get("totalDebitCents").asLong()).isEqualTo(10000L);
        assertThat(data.get("totalCreditCents").asLong()).isEqualTo(10000L);
        assertThat(data.get("entries").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("GET /v1/ledger/journals/{id} returns 404 when requested by unauthorized merchant")
    void testGetJournalById_CrossMerchantForbidden() {
        Payment payment = paymentRepository.save(
                Payment.builder()
                        .merchant(merchantA)
                        .customer(customerA)
                        .amountCents(10000L)
                        .currency("USD")
                        .status(PaymentStatus.CAPTURED)
                        .idempotencyKey("pay_cross_" + UUID.randomUUID())
                        .build()
        );
        Journal createdJournal = ledgerService.recordPaymentCapture(payment, UUID.randomUUID());

        // Merchant B tries to read Merchant A's journal
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(merchantB.getId()));
        ResponseEntity<String> response = restTemplate.exchange("/v1/ledger/journals/" + createdJournal.getId(), HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /v1/ledger/journals/{id} returns 404 for non-existent journal")
    void testGetJournalById_NotFound() {
        UUID randomId = UUID.randomUUID();
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(merchantA.getId()));
        ResponseEntity<String> response = restTemplate.exchange("/v1/ledger/journals/" + randomId, HttpMethod.GET, entity, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
