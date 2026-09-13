package com.lky.kaipay.common.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lky.kaipay.customer.domain.Customer;
import com.lky.kaipay.merchant.domain.Merchant;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.domain.PaymentStatus;
import com.lky.kaipay.payment.domain.event.PaymentInitiatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Event Envelope and Domain Event Unit Tests")
class EventEnvelopeUnitTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Nested
    @DisplayName("Factory and Builder Tests")
    class FactoryTests {

        @Test
        @DisplayName("EventEnvelope.of creates envelope with default metadata")
        void eventEnvelopeOfCreatesValidEnvelope() {
            UUID merchantId = UUID.randomUUID();
            PaymentInitiatedEvent payload = PaymentInitiatedEvent.builder()
                    .paymentId(UUID.randomUUID())
                    .merchantId(merchantId)
                    .customerId(UUID.randomUUID())
                    .amountCents(5000L)
                    .currency("USD")
                    .idempotencyKey("idem-123")
                    .build();

            EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.of(
                    "PAYMENT_INITIATED",
                    "PAYMENT",
                    payload.getPaymentId().toString(),
                    merchantId,
                    payload
            );

            assertThat(envelope.getEventId()).isNotNull();
            assertThat(envelope.getEventType()).isEqualTo("PAYMENT_INITIATED");
            assertThat(envelope.getAggregateType()).isEqualTo("PAYMENT");
            assertThat(envelope.getAggregateId()).isEqualTo(payload.getPaymentId().toString());
            assertThat(envelope.getMerchantId()).isEqualTo(merchantId);
            assertThat(envelope.getTimestamp()).isNotNull();
            assertThat(envelope.getVersion()).isEqualTo(1);
            assertThat(envelope.getPayload()).isEqualTo(payload);
        }

        @Test
        @DisplayName("PaymentInitiatedEvent.fromPayment extracts all fields correctly")
        void paymentInitiatedEventFromPayment() {
            UUID paymentId = UUID.randomUUID();
            UUID merchantId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();

            Merchant merchant = Merchant.builder().id(merchantId).name("Test Merchant").build();
            Customer customer = Customer.builder().id(customerId).fullName("Test Customer").build();

            Payment payment = Payment.builder()
                    .id(paymentId)
                    .merchant(merchant)
                    .customer(customer)
                    .amountCents(7500L)
                    .currency("EUR")
                    .status(PaymentStatus.CREATED)
                    .idempotencyKey("test-key-999")
                    .build();

            PaymentInitiatedEvent event = PaymentInitiatedEvent.fromPayment(payment);

            assertThat(event).isNotNull();
            assertThat(event.getPaymentId()).isEqualTo(paymentId);
            assertThat(event.getMerchantId()).isEqualTo(merchantId);
            assertThat(event.getCustomerId()).isEqualTo(customerId);
            assertThat(event.getAmountCents()).isEqualTo(7500L);
            assertThat(event.getCurrency()).isEqualTo("EUR");
            assertThat(event.getIdempotencyKey()).isEqualTo("test-key-999");
        }

        @Test
        @DisplayName("PaymentInitiatedEvent.fromPayment returns null for null payment")
        void paymentInitiatedEventFromNullPayment() {
            assertThat(PaymentInitiatedEvent.fromPayment(null)).isNull();
        }
    }

    @Nested
    @DisplayName("JSON Serialization and Deserialization")
    class JsonSerializationTests {

        @Test
        @DisplayName("EventEnvelope serializes and deserializes preserving all metadata and payload fields")
        void envelopeJsonRoundTrip() throws Exception {
            UUID eventId = UUID.randomUUID();
            UUID merchantId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            UUID customerId = UUID.randomUUID();
            Instant timestamp = Instant.parse("2026-08-24T09:30:00Z");

            PaymentInitiatedEvent payload = PaymentInitiatedEvent.builder()
                    .paymentId(paymentId)
                    .merchantId(merchantId)
                    .customerId(customerId)
                    .amountCents(12900L)
                    .currency("USD")
                    .idempotencyKey("key-roundtrip-1")
                    .build();

            EventEnvelope<PaymentInitiatedEvent> envelope = EventEnvelope.<PaymentInitiatedEvent>builder()
                    .eventId(eventId)
                    .eventType("PAYMENT_INITIATED")
                    .aggregateType("PAYMENT")
                    .aggregateId(paymentId.toString())
                    .merchantId(merchantId)
                    .timestamp(timestamp)
                    .version(1)
                    .payload(payload)
                    .build();

            String json = objectMapper.writeValueAsString(envelope);

            assertThat(json).contains("\"eventId\":\"" + eventId + "\"");
            assertThat(json).contains("\"eventType\":\"PAYMENT_INITIATED\"");
            assertThat(json).contains("\"aggregateType\":\"PAYMENT\"");
            assertThat(json).contains("\"aggregateId\":\"" + paymentId + "\"");
            assertThat(json).contains("\"merchantId\":\"" + merchantId + "\"");
            assertThat(json).contains("\"version\":1");
            assertThat(json).contains("\"amountCents\":12900");
            assertThat(json).contains("\"currency\":\"USD\"");
            assertThat(json).contains("\"idempotencyKey\":\"key-roundtrip-1\"");

            EventEnvelope<PaymentInitiatedEvent> deserialized = objectMapper.readValue(
                    json,
                    new TypeReference<EventEnvelope<PaymentInitiatedEvent>>() {}
            );

            assertThat(deserialized.getEventId()).isEqualTo(eventId);
            assertThat(deserialized.getEventType()).isEqualTo("PAYMENT_INITIATED");
            assertThat(deserialized.getAggregateType()).isEqualTo("PAYMENT");
            assertThat(deserialized.getAggregateId()).isEqualTo(paymentId.toString());
            assertThat(deserialized.getMerchantId()).isEqualTo(merchantId);
            assertThat(deserialized.getTimestamp()).isEqualTo(timestamp);
            assertThat(deserialized.getVersion()).isEqualTo(1);

            PaymentInitiatedEvent deserializedPayload = deserialized.getPayload();
            assertThat(deserializedPayload).isNotNull();
            assertThat(deserializedPayload.getPaymentId()).isEqualTo(paymentId);
            assertThat(deserializedPayload.getMerchantId()).isEqualTo(merchantId);
            assertThat(deserializedPayload.getCustomerId()).isEqualTo(customerId);
            assertThat(deserializedPayload.getAmountCents()).isEqualTo(12900L);
            assertThat(deserializedPayload.getCurrency()).isEqualTo("USD");
            assertThat(deserializedPayload.getIdempotencyKey()).isEqualTo("key-roundtrip-1");
        }
    }
}
