package com.lky.kaipay.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.common.exception.InvalidJournalException;
import com.lky.kaipay.common.exception.InvalidRefundException;
import com.lky.kaipay.common.exception.InvalidRequestException;
import com.lky.kaipay.ledger.api.dto.JournalResponse;
import com.lky.kaipay.ledger.domain.*;
import com.lky.kaipay.ledger.repository.AccountRepository;
import com.lky.kaipay.ledger.repository.JournalRepository;
import com.lky.kaipay.ledger.service.AccountProvisioningService;
import com.lky.kaipay.ledger.service.LedgerService;
import com.lky.kaipay.ledger.service.MerchantBalanceService;
import com.lky.kaipay.ledger.service.dto.LedgerEntryRequest;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.service.AuthorizationDelivery;
import com.lky.kaipay.payment.service.AuthorizationRecoveryService;
import com.lky.kaipay.payment.service.PaymentService;
import com.lky.kaipay.payment.service.IdempotencyService;
import com.lky.kaipay.payment.service.acquirer.AuthorizationRequest;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import com.lky.kaipay.refund.api.dto.CreateRefundRequest;
import com.lky.kaipay.refund.service.RefundService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL assertions for new MYR activity and preserved historical units. */
class MyrCurrencyIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TestRestTemplate http;
    @Autowired PaymentService payments;
    @Autowired IdempotencyService idempotency;
    @Autowired RefundService refunds;
    @Autowired LedgerService ledger;
    @Autowired MerchantBalanceService balances;
    @Autowired AccountRepository accounts;
    @Autowired JournalRepository journals;
    @Autowired MerchantRepository merchants;
    @Autowired AuthorizationRecoveryService recovery;
    @Autowired MockBankAcquirerClient simulator;
    UUID merchant;
    UUID customer;

    @BeforeEach void fixture() {
        clearFinancialFixtures();
        merchant = UUID.randomUUID();
        customer = UUID.randomUUID();
        jdbc.update("INSERT INTO merchants(id,name,api_key_hash,status) VALUES (?,'MYR fixture',?,'ACTIVE')", merchant, merchant.toString());
        jdbc.update("INSERT INTO customers(id,merchant_id,email,full_name) VALUES (?,?,'myr@example.test','MYR fixture')", customer, merchant);
    }

    @AfterEach void cleanup() {
        clearFinancialFixtures();
        jdbc.update("DELETE FROM demo_users");
        jdbc.update("DELETE FROM customers WHERE id=?", customer);
        jdbc.update("DELETE FROM merchants WHERE id=?", merchant);
    }

    private void clearFinancialFixtures() {
        // These tables belong to this disposable Testcontainers database, never an application database.
        for (String table : List.of("ledger_entries", "journals", "refunds", "accounts", "payment_events_outbox",
                "consumed_events", "simulated_authorization_operations", "idempotency_records", "payments")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test void apiPersistsExactIntegerSenAndReplaysWithoutDuplicateEffects() throws Exception {
        HttpHeaders headers = headers("create-sen");
        String body = createBody("MYR", "101");
        var first = http.exchange("/v1/payments", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        var replay = http.exchange("/v1/payments", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode original = mapper.readTree(first.getBody()).path("data");
        assertThat(mapper.readTree(replay.getBody()).path("data")).isEqualTo(original);
        assertThat(original.path("currency").asText()).isEqualTo("MYR");
        assertThat(original.path("amountCents").asLong()).isEqualTo(101); // RM1.01, no decimal storage.
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM payments", Long.class)).isEqualTo(101L);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("payment_events_outbox")).isEqualTo(1);
        assertThat(count("idempotency_records")).isEqualTo(1);
        JsonNode event = mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM payment_events_outbox", String.class));
        assertThat(event.path("payload").path("currency").asText()).isEqualTo("MYR");
        assertThat(event.path("payload").path("amountCents").asLong()).isEqualTo(101);
        assertNoAccounting();
        assertThat(http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(createBody("MYR", "102"), headers), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("payment_events_outbox")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"USD", "EUR", "GBP", "myr", "MYR ", ""})
    void unsupportedCurrenciesFailAtApiAndServiceWithoutBusinessWrites(String currency) {
        var response = http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(createBody(currency, "100"), headers(UUID.randomUUID().toString())), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var request = CreatePaymentRequest.builder().customerId(customer).amountCents(100L).currency(currency).build();
        assertThatThrownBy(() -> payments.createPayment(merchant, "direct", request)).isInstanceOf(InvalidRequestException.class);
        assertThat(count("payments")).isZero();
        assertThat(count("idempotency_records")).isZero();
        assertThat(count("payment_events_outbox")).isZero();
        assertThat(count("simulated_authorization_operations")).isZero();
        assertNoAccounting();
    }

    @Test void missingCurrencyCannotDefaultIntoNewFinancialActivity() {
        var response = http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>("{\"customerId\":\"" + customer + "\",\"amountCents\":100}", headers("missing")), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThatThrownBy(() -> payments.createPayment(merchant, "missing-direct",
                CreatePaymentRequest.builder().customerId(customer).amountCents(100L).build()))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(count("payments")).isZero();
        assertThat(count("payment_events_outbox")).isZero();
        assertNoAccounting();
    }

    @ParameterizedTest @ValueSource(strings = {"1.5", "\"100\"", "9223372036854775808", "true", "0", "-1"})
    void apiRejectsNonIntegerSenWithoutCreationOrRefundEffects(String amountJson) {
        HttpHeaders headers = headers("strict-sen-" + UUID.randomUUID());
        assertThat(http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(createBody("MYR", amountJson), headers), String.class).getStatusCode())
                .as("new payment amountCents=%s", amountJson).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("payments")).isZero();
        assertThat(count("idempotency_records")).isZero();
        assertThat(count("payment_events_outbox")).isZero();
        UUID captured = payment(10000, "MYR", "CAPTURED");
        var before = financialRows();
        assertThat(http.exchange("/v1/payments/" + captured + "/refunds", HttpMethod.POST,
                new HttpEntity<>("{\"amountCents\":" + amountJson + "}", headers), String.class).getStatusCode())
                .as("new refund amountCents=%s", amountJson).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(financialRows()).isEqualTo(before);
        assertThat(count("refunds")).isZero();
        assertNoAccounting();
    }

    @Test void exactHistoricalCreationReplayRemainsAvailableWithoutAdmittingNewUsdActivity() throws Exception {
        UUID historical = payment(12345, "USD", "CREATED");
        jdbc.update("UPDATE payments SET idempotency_key='historical-create' WHERE id=?", historical);
        var request = CreatePaymentRequest.builder().customerId(customer).currency("USD").amountCents(12345L).build();
        var response = payments.getPayment(merchant, historical);
        idempotency.recordResponse(merchant, "historical-create", idempotency.computeRequestHash(request), 201, response);
        var before = financialRows();
        HttpHeaders headers = headers("historical-create");
        var replay = http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(createBody("USD", "12345"), headers), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var data = mapper.readTree(replay.getBody()).path("data");
        assertThat(data.path("id").asText()).isEqualTo(historical.toString());
        assertThat(data.path("currency").asText()).isEqualTo("USD");
        assertThat(data.path("amountCents").asLong()).isEqualTo(12345);
        assertThat(http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(createBody("USD", "12346"), headers), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange("/v1/payments", HttpMethod.POST,
                new HttpEntity<>(createBody("USD", "12345"), headers("new-usd")), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(financialRows()).isEqualTo(before);
        assertThat(count("idempotency_records")).isEqualTo(1);
        assertNoAccounting();
    }

    @Test void captureAndRefundKeepSenAndExistingSimulatedFeeFormula() {
        UUID payment = payment(12000, "MYR", "AUTHORIZED");
        var capture = payments.capturePayment(merchant, payment, "capture");
        assertThat(capture.getCurrency()).isEqualTo("MYR");
        assertThat(capture.getAmountCents()).isEqualTo(12000L);
        assertThat(balance(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC)).isEqualTo(-12000);
        assertThat(balance(AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC)).isEqualTo(378);
        var payable = accounts.findByMerchantIdAndAccountType(merchant, AccountType.LIABILITY).orElseThrow();
        assertThat(ledger.getAccountBalance(payable.getId())).isEqualTo(11622);
        var partial = refunds.createRefund(merchant, payment, "refund-40", refundRequest(4000));
        assertThat(partial.getCurrency()).isEqualTo("MYR");
        assertThat(partial.getAmountCents()).isEqualTo(4000);
        refunds.createRefund(merchant, payment, "refund-80", refundRequest(8000));
        var stableRows = financialRows();
        assertThat(refunds.createRefund(merchant, payment, "refund-40", refundRequest(4000)).getId()).isEqualTo(partial.getId());
        assertThat(financialRows()).isEqualTo(stableRows);
        assertThat(balance(AccountProvisioningService.SYS_CUSTOMER_RECEIVABLE_ACC)).isZero();
        assertThat(balance(AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC)).isEqualTo(30);
        assertThat(ledger.getAccountBalance(payable.getId())).isEqualTo(-30);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, payment)).isEqualTo("REFUNDED");
        assertThat(count("journals")).isEqualTo(3);
        assertThat(count("refunds")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT DISTINCT currency FROM ledger_entries", String.class)).containsExactly("MYR");
        assertThat(jdbc.queryForList("SELECT DISTINCT currency FROM accounts", String.class)).containsExactly("MYR");
        assertThat(jdbc.queryForList("SELECT j.id FROM journals j JOIN ledger_entries e ON e.journal_id=j.id GROUP BY j.id HAVING SUM(CASE WHEN entry_type='DEBIT' THEN amount_cents ELSE -amount_cents END) <> 0"))
                .isEmpty();
    }

    @Test void historicalUsdReadsAndRefundReplayPreserveOriginalMoneyButNewWritesAreBlocked() throws Exception {
        UUID authorized = payment(12345, "USD", "AUTHORIZED");
        UUID captured = payment(9000, "USD", "CAPTURED");
        UUID oldRefund = storedRefund(captured, 1000, "USD", "old-usd-refund");
        HttpHeaders headers = headers("historical");
        var before = financialRows();
        var read = http.exchange("/v1/payments/" + authorized, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(read.getBody()).path("data").path("currency").asText()).isEqualTo("USD");
        assertThat(mapper.readTree(read.getBody()).path("data").path("amountCents").asLong()).isEqualTo(12345);
        assertThat(http.exchange("/v1/payments/" + authorized + "/capture", HttpMethod.POST,
                new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/v1/payments/" + captured + "/refunds", HttpMethod.POST,
                new HttpEntity<>("{\"amountCents\":100}", headers), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refunds.createRefund(merchant, captured, "old-usd-refund", refundRequest(1000)).getId()).isEqualTo(oldRefund);
        assertThat(payments.capturePayment(merchant, captured, "already-captured").getCurrency()).isEqualTo("USD");
        assertThat(refunds.getPaymentRefunds(merchant, captured)).singleElement().satisfies(r -> {
            assertThat(r.getCurrency()).isEqualTo("USD");
            assertThat(r.getAmountCents()).isEqualTo(1000);
        });
        assertThat(financialRows()).isEqualTo(before);
        assertNoAccounting();
    }

    @Test void merchantProjectionAggregatesOnlyMyrAcrossEveryMoneySource() {
        UUID myr = payment(12000, "MYR", "AUTHORIZED");
        payments.capturePayment(merchant, myr, "capture");
        refunds.createRefund(merchant, myr, "refund", refundRequest(4000));
        payment(700, "MYR", "AUTHORIZED");
        UUID usd = payment(990000, "USD", "CAPTURED");
        payment(880000, "USD", "AUTHORIZED");
        storedRefund(usd, 110000, "USD", "historical");
        // A malformed historical MYR refund on a USD payment must also stay out of MYR totals.
        storedRefund(usd, 220000, "MYR", "historical-mismatch");
        Account usdRevenue = account("historical-usd-revenue", AccountType.REVENUE, "USD", false);
        Account usdAsset = account("historical-usd-asset", AccountType.ASSET, "USD", false);
        storedJournal(usdAsset, "USD", 333000, usdRevenue, "USD", 333000);
        var result = balances.getMerchantBalance(merchant);
        assertThat(result.getCurrency()).isEqualTo("MYR");
        assertThat(result.getAvailableBalanceCents()).isEqualTo(7738);
        assertThat(result.getPendingSettlementCents()).isEqualTo(700);
        assertThat(result.getTotalVolumeCents()).isEqualTo(12000);
        assertThat(result.getTotalFeesCents()).isEqualTo(262);
        assertThat(result.getTotalRefundsCents()).isEqualTo(4000);
        assertThat(jdbc.queryForObject("SELECT currency FROM payments WHERE id=?", String.class, usd)).isEqualTo("USD");
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM payments WHERE id=?", Long.class, usd)).isEqualTo(990000L);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void incompatibleSystemOrMerchantAccountRollsBackWholeCapture(boolean merchantAccount) {
        UUID payment = payment(10000, "MYR", "AUTHORIZED");
        account(merchantAccount ? "legacy-merchant-liability" : AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC,
                merchantAccount ? AccountType.LIABILITY : AccountType.REVENUE, "USD", merchantAccount);
        var before = financialRows();
        assertThatThrownBy(() -> payments.capturePayment(merchant, payment, "incompatible"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("account currency");
        assertThat(financialRows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, payment)).isEqualTo("AUTHORIZED");
        assertThat(count("accounts")).isEqualTo(1); // Earlier MYR provisioning also rolled back.
        assertThat(count("journals")).isZero();
        assertThat(count("payment_events_outbox")).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void incompatibleSystemOrMerchantAccountRollsBackWholeRefund(boolean merchantAccount) {
        UUID payment = payment(10000, "MYR", "CAPTURED");
        account(merchantAccount ? "legacy-merchant-liability" : AccountProvisioningService.SYS_PLATFORM_REVENUE_ACC,
                merchantAccount ? AccountType.LIABILITY : AccountType.REVENUE, "USD", merchantAccount);
        var before = financialRows();
        // The failing ledger call follows refund insertion, payment transition and outbox insertion.
        assertThatThrownBy(() -> refunds.createRefund(merchant, payment, "incompatible-refund", refundRequest(1000)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("account currency");
        assertThat(financialRows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, payment)).isEqualTo("CAPTURED");
        assertThat(count("refunds")).isZero();
        assertThat(count("payment_events_outbox")).isZero();
        assertThat(count("journals")).isZero();
        assertThat(count("ledger_entries")).isZero();
        assertThat(count("accounts")).isEqualTo(1); // Any earlier MYR provisioning rolls back too.
    }

    @Test void newJournalsRejectMixedUnitsAndEntryAccountMismatchWithoutPosting() {
        Account myr = account("myr-asset", AccountType.ASSET, "MYR", false);
        Account usd = account("usd-liability", AccountType.LIABILITY, "USD", false);
        for (List<LedgerEntryRequest> entries : List.of(
                List.of(new LedgerEntryRequest(myr, EntryType.DEBIT, 100, "MYR"), new LedgerEntryRequest(usd, EntryType.CREDIT, 100, "USD")),
                List.of(new LedgerEntryRequest(myr, EntryType.DEBIT, 100, "MYR"), new LedgerEntryRequest(usd, EntryType.CREDIT, 100, "MYR")),
                List.of(new LedgerEntryRequest(myr, EntryType.DEBIT, 100, null), new LedgerEntryRequest(myr, EntryType.CREDIT, 100, null)))) {
            assertThatThrownBy(() -> ledger.postJournal(JournalSourceType.SETTLEMENT, UUID.randomUUID().toString(), UUID.randomUUID(),
                    merchants.findById(merchant).orElseThrow(), "Rejected units", entries)).isInstanceOf(InvalidJournalException.class);
        }
        assertThat(count("journals")).isZero();
        assertThat(count("ledger_entries")).isZero();
        assertThat(count("accounts")).isEqualTo(2);
    }

    @Test void historicalJournalProjectionKeepsRowsAndSuppressesMisleadingMixedTotals() {
        Account myr = account("myr", AccountType.ASSET, "MYR", false);
        Account usd = account("usd", AccountType.LIABILITY, "USD", false);
        UUID coherent = storedJournal(usd, "USD", 123, usd, "USD", 123);
        var historical = JournalResponse.fromEntity(journals.findById(coherent).orElseThrow());
        assertThat(historical.getCurrency()).isEqualTo("USD");
        assertThat(historical.getTotalDebitCents()).isEqualTo(123L);
        assertThat(historical.getTotalCreditCents()).isEqualTo(123L);
        UUID mixed = storedJournal(myr, "MYR", 100, usd, "USD", 100);
        UUID mismatch = storedJournal(myr, "MYR", 100, usd, "MYR", 100);
        for (UUID id : List.of(mixed, mismatch)) {
            var projection = JournalResponse.fromEntity(journals.findById(id).orElseThrow());
            assertThat(projection.getCurrency()).isNull();
            assertThat(projection.getTotalDebitCents()).isNull();
            assertThat(projection.getTotalCreditCents()).isNull();
            assertThat(projection.getEntries()).hasSize(2);
            JsonNode json = mapper.valueToTree(projection);
            assertThat(json.has("currency")).isFalse();
            assertThat(json.has("totalDebitCents")).isFalse();
            assertThat(json.has("totalCreditCents")).isFalse();
            assertThat(json.path("entries").size()).isEqualTo(2);
        }
        assertThatThrownBy(() -> ledger.getAccountBalance(usd.getId())).isInstanceOf(InvalidJournalException.class);
        assertThat(count("ledger_entries")).isEqualTo(6);
    }

    @Test void mixedStoredRefundBlocksNewRefundAndMixedAccountBlocksBalance() {
        UUID payment = payment(12000, "MYR", "AUTHORIZED");
        payments.capturePayment(merchant, payment, "capture");
        storedRefund(payment, 100, "USD", "wrong-units");
        var before = financialRows();
        assertThatThrownBy(() -> refunds.createRefund(merchant, payment, "new-refund", refundRequest(500)))
                .isInstanceOf(InvalidRefundException.class).hasMessageContaining("currency");
        assertThat(financialRows()).isEqualTo(before);
        Account payable = accounts.findByMerchantIdAndAccountType(merchant, AccountType.LIABILITY).orElseThrow();
        storedJournal(payable, "USD", 500, payable, "USD", 500);
        assertThatThrownBy(() -> balances.getMerchantBalance(merchant)).isInstanceOf(InvalidJournalException.class);
        assertThatThrownBy(() -> ledger.getAccountBalance(payable.getId())).isInstanceOf(InvalidJournalException.class);
    }

    @Test void historicalDefinitiveRecoveryUsesRetainedResultWithoutNewEvaluationOrMoneyRelabeling() {
        UUID payment = payment(5555, "USD", "PROCESSING");
        seedOperation(payment, true);
        var originalEvidence = jdbc.queryForMap("SELECT * FROM simulated_authorization_operations WHERE payment_id=?", payment);
        var delivery = delivery(payment, 5555, "USD");
        AuthorizationRequest request = recovery.prepareAuthorization(delivery, "myr-history").orElseThrow();
        var retained = simulator.authorize(request);
        assertThat(retained.isApproved()).isTrue();
        assertThat(retained.getAuthorizationCode()).isEqualTo("AUTH-HISTORICAL");
        recovery.completeAuthorization(delivery, "myr-history", retained);
        assertThat(recovery.prepareAuthorization(delivery, "myr-history")).isEmpty();
        assertThat(jdbc.queryForMap("SELECT * FROM simulated_authorization_operations WHERE payment_id=?", payment)).isEqualTo(originalEvidence);
        assertThat(jdbc.queryForMap("SELECT amount_cents,currency,status,gateway_reference FROM payments WHERE id=?", payment))
                .containsEntry("amount_cents", 5555L).containsEntry("currency", "USD")
                .containsEntry("status", "AUTHORIZED").containsEntry("gateway_reference", "AUTH-HISTORICAL");
        assertThat(count("consumed_events")).isEqualTo(1);
        assertNoAccounting();
    }

    @Test void nonMyrCreationAndUnresolvedRecoveryNeverInventAcceptance() {
        UUID created = payment(4444, "USD", "CREATED");
        assertThatThrownBy(() -> recovery.prepareAuthorization(delivery(created, 4444, "USD"), "myr-history"))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(count("simulated_authorization_operations")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, created)).isEqualTo("CREATED");
        UUID unresolved = payment(777700, "USD", "PROCESSING");
        seedOperation(unresolved, false);
        var before = jdbc.queryForMap("SELECT * FROM simulated_authorization_operations WHERE payment_id=?", unresolved);
        assertThatThrownBy(() -> recovery.prepareAuthorization(delivery(unresolved, 777700, "USD"), "myr-history"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("historical");
        assertThatThrownBy(() -> simulator.authorize(new AuthorizationRequest(unresolved, merchant, 777700, "USD")))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM simulated_authorization_operations WHERE payment_id=?", unresolved)).isEqualTo(before);
        assertThat(count("consumed_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, unresolved)).isEqualTo("PROCESSING");
        assertNoAccounting();
    }

    @Test void v9ChangesDefaultsAndPreservesEveryHistoricalFinancialRow() {
        String schema = "myr_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var sql = new JdbcTemplate(source);
        try {
            Flyway.configure().dataSource(source).schemas(schema).target("8").load().migrate();
            UUID oldMerchant = UUID.fromString("11111111-1111-1111-1111-111111111111");
            UUID oldCustomer = UUID.fromString("22222222-2222-2222-2222-222222222221");
            UUID payment = UUID.randomUUID(), account = UUID.randomUUID(), journal = UUID.randomUUID();
            sql.update("INSERT INTO " + schema + ".payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key) VALUES (?,?,?,12345,'USD','CAPTURED','legacy')", payment, oldMerchant, oldCustomer);
            sql.update("INSERT INTO " + schema + ".accounts(id,merchant_id,account_number,account_name,account_type) VALUES (?,?,'legacy','Legacy','LIABILITY')", account, oldMerchant);
            sql.update("INSERT INTO " + schema + ".journals(id,journal_number,source_type,source_id,merchant_id,description) VALUES (?,'legacy','PAYMENT_CAPTURE',?,?,'Legacy')", journal, payment.toString(), oldMerchant);
            sql.update("INSERT INTO " + schema + ".ledger_entries(journal_id,account_id,entry_type,amount_cents) VALUES (?,?,'CREDIT',12345)", journal, account);
            sql.update("INSERT INTO " + schema + ".refunds(payment_id,merchant_id,amount_cents,idempotency_key) VALUES (?,?,345,'legacy')", payment, oldMerchant);
            Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
            for (String table : List.of("payments", "accounts", "journals", "ledger_entries", "refunds")) {
                before.put(table, sql.queryForList("SELECT * FROM " + schema + "." + table + " ORDER BY id"));
            }
            var flyway = Flyway.configure().dataSource(source).schemas(schema).target("9").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            before.forEach((table, rows) -> assertThat(sql.queryForList("SELECT * FROM " + schema + "." + table + " ORDER BY id")).as(table).isEqualTo(rows));
            assertThat(sql.queryForObject("SELECT currency FROM " + schema + ".accounts WHERE id=?", String.class, account)).isEqualTo("USD");
            UUID newAccount = UUID.randomUUID();
            sql.update("INSERT INTO " + schema + ".accounts(id,account_number,account_name,account_type) VALUES (?,'new','New','ASSET')", newAccount);
            sql.update("INSERT INTO " + schema + ".ledger_entries(journal_id,account_id,entry_type,amount_cents) VALUES (?,?,'DEBIT',10)", journal, newAccount);
            sql.update("INSERT INTO " + schema + ".refunds(payment_id,merchant_id,amount_cents,idempotency_key) VALUES (?,?,10,'new')", payment, oldMerchant);
            assertThat(sql.queryForObject("SELECT currency FROM " + schema + ".accounts WHERE id=?", String.class, newAccount)).isEqualTo("MYR");
            assertThat(sql.queryForObject("SELECT currency FROM " + schema + ".ledger_entries WHERE account_id=?", String.class, newAccount)).isEqualTo("MYR");
            assertThat(sql.queryForObject("SELECT currency FROM " + schema + ".refunds WHERE idempotency_key='new'", String.class)).isEqualTo("MYR");
        } finally {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private UUID payment(long amount, String currency, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key,gateway_reference) VALUES (?,?,?,?,?,?,?,'AUTH-FIXTURE')",
                id, merchant, customer, amount, currency, status, id.toString());
        return id;
    }

    private UUID storedRefund(UUID payment, long amount, String currency, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO refunds(id,payment_id,merchant_id,amount_cents,currency,idempotency_key) VALUES (?,?,?,?,?,?)", id, payment, merchant, amount, currency, key);
        return id;
    }

    private Account account(String number, AccountType type, String currency, boolean merchantAccount) {
        return accounts.save(Account.builder().accountNumber(number).accountName(number).accountType(type).currency(currency)
                .merchant(merchantAccount ? merchants.findById(merchant).orElseThrow() : null).build());
    }

    private UUID storedJournal(Account debit, String debitCurrency, long debitAmount, Account credit, String creditCurrency, long creditAmount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO journals(id,journal_number,source_type,source_id,merchant_id,description) VALUES (?,?,'PAYMENT_CAPTURE',?,?,'Historical fixture')",
                id, id.toString(), id.toString(), merchant);
        jdbc.update("INSERT INTO ledger_entries(journal_id,account_id,entry_type,amount_cents,currency) VALUES (?,?,'DEBIT',?,?),(?,?,'CREDIT',?,?)",
                id, debit.getId(), debitAmount, debitCurrency, id, credit.getId(), creditAmount, creditCurrency);
        return id;
    }

    private void seedOperation(UUID payment, boolean definitive) {
        jdbc.update("INSERT INTO simulated_authorization_operations(payment_id,namespace,contract_version,merchant_id,amount_cents,currency,disposition,evaluation_count,authorization_code,result_at) "
                        + "SELECT id,'kaipay-sim-auth-v1',1,merchant_id,amount_cents,currency,?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END FROM payments WHERE id=?",
                definitive ? "APPROVED" : "UNRESOLVED", definitive ? 1 : 0, definitive ? "AUTH-HISTORICAL" : null, definitive, payment);
    }

    private AuthorizationDelivery delivery(UUID payment, long amount, String currency) {
        return new AuthorizationDelivery(UUID.randomUUID(), payment, amount, currency, merchant, customer, payment.toString(), merchant, payment, "PAYMENT");
    }

    private HttpHeaders headers(String key) {
        HttpHeaders headers = security().merchantHeaders(merchant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return headers;
    }

    private String createBody(String currency, String amount) {
        return "{\"customerId\":\"" + customer + "\",\"currency\":\"" + currency + "\",\"amountCents\":" + amount + "}";
    }

    private CreateRefundRequest refundRequest(long amount) {
        return CreateRefundRequest.builder().amountCents(amount).build();
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private long balance(String number) {
        return ledger.getAccountBalance(accounts.findByAccountNumber(number).orElseThrow().getId());
    }

    private void assertNoAccounting() {
        assertThat(count("journals")).isZero();
        assertThat(count("ledger_entries")).isZero();
        assertThat(count("accounts")).isZero();
    }

    private Map<String, List<Map<String, Object>>> financialRows() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("payments", "refunds", "accounts", "journals", "ledger_entries", "payment_events_outbox")) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return result;
    }
}
