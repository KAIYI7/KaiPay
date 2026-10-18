package com.lky.kaipay.common.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.api.GlobalExceptionHandler;
import com.lky.kaipay.payment.api.PaymentController;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.api.dto.PaymentResponse;
import com.lky.kaipay.payment.domain.IdempotencyRecord;
import com.lky.kaipay.payment.domain.IdempotencyRecordId;
import com.lky.kaipay.payment.repository.IdempotencyRecordRepository;
import com.lky.kaipay.payment.service.IdempotencyService;
import com.lky.kaipay.refund.api.RefundController;
import com.lky.kaipay.refund.api.dto.CreateRefundRequest;
import com.lky.kaipay.security.MerchantApplicationService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationPrivacyLoggingUnitTest {
    private static final String SECRET = "private-contact@example.invalid\nforged-log-entry";

    @Test void controllersAndIdempotencyPathsNeverLogOrEchoCallerKeyAndHashes() throws Exception {
        try (var logs = new Logs()) {
            var boundary = mock(MerchantApplicationService.class);
            UUID merchant = UUID.randomUUID();
            new PaymentController(boundary).createPayment(merchant, SECRET, new CreatePaymentRequest());
            new RefundController(boundary).createRefund(merchant, SECRET, UUID.randomUUID(), new CreateRefundRequest());
            var repository = mock(IdempotencyRecordRepository.class);
            var mapper = mock(ObjectMapper.class);
            when(mapper.readValue("{}", PaymentResponse.class)).thenReturn(PaymentResponse.builder().build());
            when(mapper.writeValueAsString(any())).thenReturn("{}");
            var service = new IdempotencyService(repository, mapper);
            var record = IdempotencyRecord.builder().id(new IdempotencyRecordId(merchant, SECRET))
                    .requestHash("private-stored-hash").responseBody("{}").expiresAt(Instant.now().plusSeconds(60)).build();
            when(repository.findByMerchantIdAndKey(any(), any())).thenReturn(Optional.of(record));
            assertThatThrownBy(() -> service.getExistingResponse(merchant, SECRET, "private-incoming-hash"))
                    .hasMessage("Idempotency key was already used with a different request payload");
            assertThat(service.getExistingResponse(merchant, SECRET, "private-stored-hash")).isPresent();
            service.recordResponse(merchant, SECRET, "private-stored-hash", 201, PaymentResponse.builder().build());
            org.springframework.test.util.ReflectionTestUtils.setField(record, "expiresAt", Instant.now().minusSeconds(1));
            assertThat(service.getExistingResponse(merchant, SECRET, "private-stored-hash")).isEmpty();
            assertThat(logs.content()).contains(merchant.toString(), "Idempotency conflict", "Idempotent cache hit")
                    .doesNotContain(SECRET, "private-stored-hash", "private-incoming-hash");
        }
    }

    @Test void unexpectedHttpFailureKeepsCategoryAndSqlStateWithoutUriMessageOrStack() {
        var request = new MockHttpServletRequest("GET", "/" + SECRET);
        try (var logs = new Logs()) {
            var response = new GlobalExceptionHandler().handleGenericException(
                    new IllegalStateException(SECRET, new SQLException(SECRET, "23514")), request);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().getMessage()).isEqualTo("An unexpected internal server error occurred");
            assertThat(logs.content()).contains("IllegalStateException sqlState=23514").doesNotContain(SECRET);
        }
    }

    private static final class Logs extends ListAppender<ILoggingEvent> implements AutoCloseable {
        private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logs() { start(); root.addAppender(this); }
        String content() { return list.stream().map(event -> event.getFormattedMessage()
                + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(java.util.stream.Collectors.joining("\n")); }
        @Override public void close() { root.detachAppender(this); stop(); }
    }
}
