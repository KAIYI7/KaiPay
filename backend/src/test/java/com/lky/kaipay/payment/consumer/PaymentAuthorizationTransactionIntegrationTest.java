package com.lky.kaipay.payment.consumer;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import com.lky.kaipay.consumer.domain.ConsumedEvent;
import com.lky.kaipay.consumer.repository.ConsumedEventRepository;
import com.lky.kaipay.consumer.service.ConsumerDeduplicationService;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.customer.repository.CustomerRepository;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.merchant.domain.MerchantStatus;
import com.lky.kaipay.merchant.repository.MerchantRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.repository.PaymentRepository;
import com.lky.kaipay.payment.service.PaymentService;
import com.lky.kaipay.payment.service.acquirer.AcquirerAuthorizationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Exercises local authorization-completion transactions, without calling an external gateway. */
class PaymentAuthorizationTransactionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String EVENT_TYPE = "PaymentInitiatedEvent";

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository payments;
    @Autowired private ConsumedEventRepository consumedEvents;
    @Autowired private ConsumerDeduplicationService deduplication;
    @Autowired private MerchantRepository merchants;
    @Autowired private CustomerRepository customers;

    private Merchant merchant;
    private Customer customer;
    private Payment processingPayment;

    @BeforeEach
    void setUp() {
        merchant = merchants.save(Merchant.builder()
                .name("Authorization Transaction Merchant")
                .apiKeyHash("authorization-tx-" + UUID.randomUUID())
                .status(MerchantStatus.ACTIVE)
                .build());
        customer = customers.save(Customer.builder()
                .merchant(merchant)
                .email("authorization-tx-" + UUID.randomUUID() + "@example.com")
                .fullName("Authorization Transaction Customer")
                .build());
        Payment created = payments.save(Payment.builder()
                .merchant(merchant)
                .customer(customer)
                .amountCents(5000L)
                .currency("USD")
                .status(PaymentStatus.CREATED)
                .idempotencyKey("authorization-tx-" + UUID.randomUUID())
                .build());
        paymentService.transitionToProcessing(created.getId());
        processingPayment = reload(created.getId());
        assertThat(processingPayment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @AfterEach
    void tearDown() {
        if (processingPayment != null) {
            consumedEvents.deleteAll(markers());
            payments.deleteById(processingPayment.getId());
        }
        if (customer != null) customers.deleteById(customer.getId());
        if (merchant != null) merchants.deleteById(merchant.getId());
    }

    @ParameterizedTest(name = "approved={0}: marker failure rolls back final state; retry and replay are durable")
    @ValueSource(booleans = {true, false})
    void consumedMarkerFailureRollsBackAuthorizationCompletion(boolean approved) {
        UUID eventId = UUID.randomUUID();
        String group = "authorization-tx-" + UUID.randomUUID();
        AcquirerAuthorizationResult result = approved
                ? AcquirerAuthorizationResult.approved("AUTH-TX-APPROVED")
                : AcquirerAuthorizationResult.declined("TX_DECLINED", "Transaction test decline");

        // The existing schema limits consumer_group to 100 characters. Failure occurs in real PostgreSQL
        // when the transactional service flushes, after it has attempted to change the payment.
        Throwable failure = catchThrowable(() -> paymentService.completeAuthorizationWithDeduplication(
                processingPayment.getId(), result, eventId, "g".repeat(101), EVENT_TYPE));
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        Throwable rootCause = failure;
        while (rootCause.getCause() != null) rootCause = rootCause.getCause();
        assertThat(rootCause).isInstanceOf(SQLException.class);
        assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("22001");

        assertPaymentUnchanged(processingPayment, reload(processingPayment.getId()));
        assertThat(markers()).isEmpty();

        paymentService.completeAuthorizationWithDeduplication(
                processingPayment.getId(), result, eventId, group, EVENT_TYPE);
        Payment completed = reload(processingPayment.getId());
        PaymentStatus expectedStatus = approved ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED;
        assertThat(completed.getStatus()).isEqualTo(expectedStatus);
        assertThat(completed.getVersion()).isEqualTo(processingPayment.getVersion() + 1);
        assertThat(completed.getGatewayReference()).isEqualTo(approved ? result.getAuthorizationCode() : null);
        assertThat(completed.getFailureCode()).isEqualTo(approved ? null : result.getDeclineCode());
        assertThat(completed.getFailureMessage()).isEqualTo(approved ? null : result.getDeclineMessage());
        assertThat(markers()).singleElement().satisfies(marker -> {
            assertThat(marker.getId().getEventId()).isEqualTo(eventId);
            assertThat(marker.getId().getConsumerGroup()).isEqualTo(group);
            assertThat(marker.getPaymentId()).isEqualTo(processingPayment.getId());
            assertThat(marker.getEventType()).isEqualTo(EVENT_TYPE);
            assertThat(marker.getStatus()).isEqualTo(expectedStatus.name());
        });

        ConsumedEvent originalMarker = markers().get(0);
        paymentService.completeAuthorizationWithDeduplication(
                processingPayment.getId(), result, eventId, group, EVENT_TYPE);
        assertPaymentUnchanged(completed, reload(processingPayment.getId()));
        assertThat(markers()).singleElement().satisfies(marker -> {
            assertThat(marker.getId()).isEqualTo(originalMarker.getId());
            assertThat(marker.getProcessedAt()).isEqualTo(originalMarker.getProcessedAt());
            assertThat(marker.getStatus()).isEqualTo(originalMarker.getStatus());
        });
    }

    @Test
    void sameEventIsIndependentlyConsumedByEachGroupWithoutChangingTerminalPayment() {
        UUID eventId = UUID.randomUUID();
        String groupA = "authorization-group-a-" + UUID.randomUUID();
        String groupB = "authorization-group-b-" + UUID.randomUUID();
        AcquirerAuthorizationResult result = AcquirerAuthorizationResult.approved("AUTH-GROUP-ORIGINAL");

        paymentService.completeAuthorizationWithDeduplication(
                processingPayment.getId(), result, eventId, groupA, EVENT_TYPE);
        Payment completed = reload(processingPayment.getId());
        assertThat(completed.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(deduplication.isEventConsumed(eventId, groupA)).isTrue();
        assertThat(deduplication.isEventConsumed(eventId, groupB)).isFalse();
        assertThat(markers()).hasSize(1);

        paymentService.completeAuthorizationWithDeduplication(processingPayment.getId(),
                AcquirerAuthorizationResult.approved("AUTH-MUST-NOT-REPLACE"), eventId, groupB, EVENT_TYPE);
        assertPaymentUnchanged(completed, reload(processingPayment.getId()));
        assertThat(deduplication.isEventConsumed(eventId, groupA)).isTrue();
        assertThat(deduplication.isEventConsumed(eventId, groupB)).isTrue();
        assertThat(markers()).hasSize(2).allSatisfy(marker -> {
            assertThat(marker.getId().getEventId()).isEqualTo(eventId);
            assertThat(marker.getPaymentId()).isEqualTo(processingPayment.getId());
            assertThat(marker.getStatus()).isEqualTo("AUTHORIZED");
        }).extracting(marker -> marker.getId().getConsumerGroup()).containsExactlyInAnyOrder(groupA, groupB);

        paymentService.completeAuthorizationWithDeduplication(
                processingPayment.getId(), result, eventId, groupB, EVENT_TYPE);
        assertPaymentUnchanged(completed, reload(processingPayment.getId()));
        assertThat(markers()).hasSize(2);
    }

    private Payment reload(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow();
    }

    private List<ConsumedEvent> markers() {
        return consumedEvents.findByPaymentIdOrderByProcessedAtAsc(processingPayment.getId());
    }

    private void assertPaymentUnchanged(Payment expected, Payment actual) {
        assertThat(actual.getStatus()).isEqualTo(expected.getStatus());
        assertThat(actual.getVersion()).isEqualTo(expected.getVersion());
        assertThat(actual.getGatewayReference()).isEqualTo(expected.getGatewayReference());
        assertThat(actual.getFailureCode()).isEqualTo(expected.getFailureCode());
        assertThat(actual.getFailureMessage()).isEqualTo(expected.getFailureMessage());
        assertThat(actual.getUpdatedAt()).isEqualTo(expected.getUpdatedAt());
    }
}
