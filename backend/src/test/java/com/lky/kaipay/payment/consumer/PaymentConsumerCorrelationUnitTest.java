package com.lky.kaipay.payment.consumer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.exception.GatewayTimeoutException;
import com.lky.kaipay.common.exception.GatewayUnavailableException;
import com.lky.kaipay.common.exception.NonRetryableGatewayException;
import com.lky.kaipay.common.observability.CorrelationContext;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
import com.lky.kaipay.payment.service.AuthorizationRecoveryService;
import com.lky.kaipay.payment.service.acquirer.AuthorizationRequest;
import com.lky.kaipay.payment.service.acquirer.AcquirerAuthorizationResult;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentConsumerCorrelationUnitTest {

    @Mock private AuthorizationRecoveryService recovery;
    @Mock private MockBankAcquirerClient acquirer;
    @Mock private DeadLetterEventRepository dltRepository;
    @Mock private Acknowledgment acknowledgment;

    private PaymentProcessingConsumer consumer;
    private final List<String> loggedIds = new ArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PaymentProcessingConsumer.class);
    private AppenderBase<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        consumer = new PaymentProcessingConsumer(recovery, acquirer, dltRepository,
                new ObjectMapper().findAndRegisterModules());
        MDC.put("traceId", "previous-worker");
        MDC.put("user", "retained");
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                loggedIds.add(event.getMDCPropertyMap().get("traceId"));
            }
        };
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    void allAuthorizationCallsAndAcknowledgmentRunWithinRecordContext() {
        ConsumerRecord<String, String> record = initiated();
        addHeader(record, "request-123");
        when(recovery.prepareAuthorization(any(), anyString())).thenAnswer(invocation -> {
            assertContext("request-123");
            return Optional.of(request(record));
        });
        when(acquirer.authorize(any())).thenAnswer(invocation -> {
            assertContext("request-123");
            return AcquirerAuthorizationResult.approved("AUTH");
        });
        doAnswer(invocation -> {
            assertContext("request-123");
            return null;
        }).when(recovery).completeAuthorization(any(), anyString(), any());
        assertAcknowledgmentContext("request-123");

        consumer.processPaymentRequest(record, acknowledgment);

        assertRestored();
        verify(acknowledgment).acknowledge();
    }

    @Test
    void lifecycleAndDeduplicationEarlyReturnsRestoreWorkerContext() {
        ConsumerRecord<String, String> notification = new ConsumerRecord<>("requests", 0, 1, "key",
                "{\"eventType\":\"PaymentCapturedEvent\",\"payload\":{\"paymentId\":\"" + UUID.randomUUID() + "\"}}");
        addHeader(notification, "request-123");
        assertAcknowledgmentContext("request-123");
        consumer.processPaymentRequest(notification, acknowledgment);
        assertRestored();
        verifyNoInteractions(recovery, acquirer, dltRepository);
        assertThat(loggedIds).containsOnly("request-123");

        ConsumerRecord<String, String> duplicate = initiated();
        addHeader(duplicate, "request-123");
        when(recovery.prepareAuthorization(any(), anyString())).thenAnswer(invocation -> {
            assertContext("request-123");
            return Optional.empty();
        });
        consumer.processPaymentRequest(duplicate, acknowledgment);
        assertRestored();
        verifyNoInteractions(acquirer, dltRepository);
        assertThat(loggedIds).containsOnly("request-123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "unavailable", "nonRetryable"})
    void gatewayExceptionLogsAndNormalizesHeaderBeforeRetryAndRestoresMdc(String kind) {
        ConsumerRecord<String, String> record = initiated();
        record.headers().add("X-Correlation-Id", "old".getBytes(StandardCharsets.US_ASCII));
        addHeader(record, "request-123");
        RuntimeException failure = switch (kind) {
            case "timeout" -> new GatewayTimeoutException("timeout");
            case "unavailable" -> new GatewayUnavailableException("unavailable");
            default -> new NonRetryableGatewayException("nonRetryable");
        };
        when(recovery.prepareAuthorization(any(), anyString())).thenReturn(Optional.of(request(record)));
        when(acquirer.authorize(any())).thenAnswer(invocation -> {
            assertContext("request-123");
            throw failure;
        });

        assertThatThrownBy(() -> consumer.processPaymentRequest(record, acknowledgment))
                .isSameAs(failure);

        assertThat(record.headers().headers("X-Correlation-Id")).hasSize(1);
        assertThat(loggedIds).containsOnly("request-123");
        assertRestored();
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    void fatalPoisonMessageGetsStableIdAndLogsWithinScope() {
        ConsumerRecord<String, String> first = new ConsumerRecord<>("requests", 2, 42, "key", "{}");
        ConsumerRecord<String, String> replay = new ConsumerRecord<>("requests", 2, 42, "key", "{}");
        addHeader(first, "bad value");
        assertThatThrownBy(() -> consumer.processPaymentRequest(first, acknowledgment)).isInstanceOf(RuntimeException.class);
        assertRestored();
        String effective = new String(first.headers().lastHeader("X-Correlation-Id").value(), StandardCharsets.US_ASCII);
        assertThat(UUID.fromString(effective)).isNotNull();
        assertThatThrownBy(() -> consumer.processPaymentRequest(replay, acknowledgment)).isInstanceOf(RuntimeException.class);
        assertThat(new String(replay.headers().lastHeader("X-Correlation-Id").value(), StandardCharsets.US_ASCII)).isEqualTo(effective);
        assertThat(loggedIds).containsOnly(effective);
        assertRestored();
        verify(acknowledgment, never()).acknowledge();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcd", "abcdefgh"})
    void dltStoresFourAndEightByteIdsAsStringsAndScopesPersistenceAndAck(String correlationId) {
        ConsumerRecord<String, String> record = initiated();
        addHeader(record, correlationId);
        when(dltRepository.save(any())).thenAnswer(invocation -> {
            assertContext(correlationId);
            DeadLetterEvent saved = invocation.getArgument(0);
            assertThat(saved.getHeaders()).containsEntry("X-Correlation-Id", correlationId);
            assertThat(saved.getHeaders().get("X-Correlation-Id")).isInstanceOf(String.class);
            return saved;
        });
        assertAcknowledgmentContext(correlationId);

        consumer.handleDltMessage(record, "Failure", null, "failed", acknowledgment);

        assertThat(loggedIds).containsOnly(correlationId);
        assertRestored();
        verify(acknowledgment).acknowledge();
    }

    @Test
    void dltPersistenceFailurePropagatesWithoutAcknowledgmentAndRestoresMdc() {
        ConsumerRecord<String, String> record = initiated();
        addHeader(record, "dlt-request");
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(dltRepository.save(any())).thenAnswer(invocation -> {
            assertContext("dlt-request");
            throw failure;
        });

        assertThatThrownBy(() -> consumer.handleDltMessage(record, "Failure", null, "failed", acknowledgment))
                .isSameAs(failure);

        verify(acknowledgment, never()).acknowledge();
        assertThat(loggedIds).containsOnly("dlt-request");
        assertRestored();
    }

    @Test
    void dltNeverInvokesPaymentRecoveryWhenEvidenceCanBePersisted() {
        ConsumerRecord<String, String> record = initiated();
        addHeader(record, "dlt-request");
        when(dltRepository.save(any())).thenAnswer(invocation -> {
            assertContext("dlt-request");
            return invocation.getArgument(0);
        });
        assertAcknowledgmentContext("dlt-request");

        consumer.handleDltMessage(record, "Failure", null, "failed", acknowledgment);

        verify(dltRepository).save(any());
        verifyNoInteractions(recovery, acquirer);
        verify(acknowledgment).acknowledge();
        assertThat(loggedIds).containsOnly("dlt-request");
        assertRestored();
    }

    @Test
    void dltAcknowledgmentFailureAfterSuccessfulPersistenceRestoresMdc() {
        ConsumerRecord<String, String> record = initiated();
        addHeader(record, "dlt-request");
        when(dltRepository.save(any())).thenAnswer(invocation -> {
            assertContext("dlt-request");
            return invocation.getArgument(0);
        });
        doAnswer(invocation -> {
            assertContext("dlt-request");
            throw new IllegalStateException("ack failed");
        }).when(acknowledgment).acknowledge();

        assertThatThrownBy(() -> consumer.handleDltMessage(record, "Failure", null, "failed", acknowledgment))
                .isInstanceOf(IllegalStateException.class).hasMessage("ack failed");

        verify(dltRepository).save(any());
        assertThat(loggedIds).containsOnly("dlt-request");
        assertRestored();
    }

    private ConsumerRecord<String, String> initiated() {
        return new ConsumerRecord<>("requests", 0, 1, "key",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"payload\":{\"paymentId\":\"" + UUID.randomUUID()
                        + "\",\"amountCents\":100,\"currency\":\"MYR\"}}");
    }

    private AuthorizationRequest request(ConsumerRecord<String, String> record) {
        return new AuthorizationRequest(UUID.randomUUID(), UUID.randomUUID(), 100, "MYR");
    }

    private void addHeader(ConsumerRecord<String, String> record, String id) {
        record.headers().add(CorrelationContext.HEADER_NAME, id.getBytes(StandardCharsets.UTF_8));
    }

    private void assertAcknowledgmentContext(String id) {
        doAnswer(invocation -> {
            assertContext(id);
            return null;
        }).when(acknowledgment).acknowledge();
    }

    private void assertContext(String id) {
        assertThat(MDC.get("traceId")).isEqualTo(id);
        assertThat(MDC.get("user")).isEqualTo("retained");
    }

    private void assertRestored() {
        assertContext("previous-worker");
    }
}
