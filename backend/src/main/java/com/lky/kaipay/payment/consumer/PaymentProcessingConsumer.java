package com.lky.kaipay.payment.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.common.exception.GatewayTimeoutException;
import com.lky.kaipay.common.exception.GatewayUnavailableException;
import com.lky.kaipay.common.exception.NonRetryableGatewayException;
import com.lky.kaipay.consumer.service.ConsumerDeduplicationService;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
import com.lky.kaipay.payment.domain.Payment;
import com.lky.kaipay.payment.service.PaymentService;
import com.lky.kaipay.payment.service.acquirer.AcquirerAuthorizationResult;
import com.lky.kaipay.payment.service.acquirer.MockBankAcquirerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentProcessingConsumer {

    private final PaymentService paymentService;
    private final MockBankAcquirerClient mockBankAcquirerClient;
    private final ConsumerDeduplicationService consumerDeduplicationService;
    private final DeadLetterEventRepository deadLetterEventRepository;
    private final ObjectMapper objectMapper;

    @Value("${spring.kafka.consumer.group-id:kaipay-payment-processor-group}")
    private String consumerGroup = "kaipay-payment-processor-group";

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            dltTopicSuffix = "-dlt",
            retryTopicSuffix = "-retry",
            sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
            autoCreateTopics = "true",
            include = {GatewayTimeoutException.class, GatewayUnavailableException.class}
    )
    @KafkaListener(
            topics = "${kaipay.kafka.topics.payment-requests:kaipay.payment.requests}",
            groupId = "${spring.kafka.consumer.group-id:kaipay-payment-processor-group}"
    )
    public void processPaymentRequest(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            JsonNode rootNode = objectMapper.readTree(record.value());
            JsonNode payloadNode = rootNode.has("payload") ? rootNode.get("payload") : rootNode;
            if (!payloadNode.has("paymentId")) {
                throw new IllegalArgumentException("Unresolvable poison pill: missing paymentId");
            }

            UUID eventId = rootNode.has("eventId") ? UUID.fromString(rootNode.get("eventId").asText()) : UUID.randomUUID();
            UUID paymentId = UUID.fromString(payloadNode.get("paymentId").asText());
            long amountCents = payloadNode.get("amountCents").asLong();
            String currency = payloadNode.has("currency") ? payloadNode.get("currency").asText() : "USD";
            String eventType = rootNode.has("eventType") ? rootNode.get("eventType").asText() : "PaymentInitiatedEvent";

            // Fast Pre-Check Deduplication
            if (consumerDeduplicationService.isEventConsumed(eventId, consumerGroup)) {
                log.info("Consumer event deduplication hit: eventId {} already processed by {}", eventId, consumerGroup);
                if (ack != null) ack.acknowledge();
                return;
            }

            // Tx 1: Persist and commit observable PROCESSING state (idempotent if already PROCESSING)
            paymentService.transitionToProcessing(paymentId);

            // Non-transactional external gateway call
            AcquirerAuthorizationResult authResult = mockBankAcquirerClient.authorize(paymentId, amountCents, currency);

            // Tx 2: Persist final AUTHORIZED/DECLINED state + ConsumedEvent atomically
            paymentService.completeAuthorizationWithDeduplication(paymentId, authResult, eventId, consumerGroup, eventType);

            if (ack != null) ack.acknowledge();
        } catch (GatewayTimeoutException | GatewayUnavailableException e) {
            log.warn("Transient gateway error during event processing [key={}]: {}", record.key(), e.getMessage());
            throw e; // Rethrow to trigger @RetryableTopic
        } catch (NonRetryableGatewayException e) {
            log.error("Non-retryable gateway error during event processing [key={}]: {}", record.key(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("Fatal error during event processing [key={}]: {}", record.key(), e.getMessage(), e);
            throw new RuntimeException("Fatal payment event processing failed", e);
        }
    }

    @DltHandler
    public void handleDltMessage(
            ConsumerRecord<String, String> record,
            @Header(value = KafkaHeaders.EXCEPTION_FQCN, required = false) String exceptionFqcn,
            @Header(value = KafkaHeaders.EXCEPTION_CAUSE_FQCN, required = false) String exceptionCauseFqcn,
            @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String exceptionMessage,
            Acknowledgment ack
    ) {
        log.error("Handling Dead Letter message from topic={}, partition={}, offset={}, key={}",
                record.topic(), record.partition(), record.offset(), record.key());

        String resolvedExceptionClass = (exceptionCauseFqcn != null && !exceptionCauseFqcn.isBlank())
                ? exceptionCauseFqcn
                : (exceptionFqcn != null ? exceptionFqcn : "UnknownException");

        UUID paymentId = null;
        UUID eventId = null;
        try {
            JsonNode rootNode = objectMapper.readTree(record.value());
            if (rootNode.has("eventId")) {
                eventId = UUID.fromString(rootNode.get("eventId").asText());
            }
            JsonNode payloadNode = rootNode.has("payload") ? rootNode.get("payload") : rootNode;
            if (payloadNode.has("paymentId")) {
                paymentId = UUID.fromString(payloadNode.get("paymentId").asText());
            }
        } catch (Exception parseEx) {
            log.warn("Could not parse payload for DLT message: {}", parseEx.getMessage());
        }

        if (paymentId != null) {
            try {
                paymentService.markPaymentFailed(paymentId, "DLT_ROUTED", exceptionMessage != null ? exceptionMessage : "Exhausted retries or fatal poison pill");
            } catch (Exception e) {
                log.error("Failed to mark payment {} as FAILED in DLT handler", paymentId, e);
            }
        }

        String rawPayload = record.value();
        String jsonPayload = rawPayload;
        try {
            objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            try {
                jsonPayload = objectMapper.writeValueAsString(rawPayload);
            } catch (Exception ex) {
                jsonPayload = "\"" + (rawPayload != null ? rawPayload.replace("\"", "\\\"").replace("\u0000", "") : "") + "\"";
            }
        }

        java.util.Map<String, Object> headersMap = new java.util.HashMap<>();
        if (record.headers() != null) {
            for (org.apache.kafka.common.header.Header h : record.headers()) {
                if (h.value() != null) {
                    byte[] val = h.value();
                    if (val.length == 4) {
                        try {
                            headersMap.put(h.key(), java.nio.ByteBuffer.wrap(val).getInt());
                        } catch (Exception e) {
                            headersMap.put(h.key(), new String(val, java.nio.charset.StandardCharsets.UTF_8).replace("\u0000", ""));
                        }
                    } else if (val.length == 8) {
                        try {
                            headersMap.put(h.key(), java.nio.ByteBuffer.wrap(val).getLong());
                        } catch (Exception e) {
                            headersMap.put(h.key(), new String(val, java.nio.charset.StandardCharsets.UTF_8).replace("\u0000", ""));
                        }
                    } else {
                        String strVal = new String(val, java.nio.charset.StandardCharsets.UTF_8).replace("\u0000", "");
                        headersMap.put(h.key(), strVal);
                    }
                }
            }
        }

        try {
            DeadLetterEvent dltEvent = DeadLetterEvent.builder()
                    .originalTopic(record.topic())
                    .originalPartition(record.partition())
                    .originalOffset(record.offset())
                    .eventId(eventId)
                    .paymentId(paymentId)
                    .exceptionClass(resolvedExceptionClass)
                    .failureMessage(exceptionMessage != null ? exceptionMessage : "DLT routed")
                    .payload(jsonPayload)
                    .headers(headersMap)
                    .build();
            deadLetterEventRepository.save(dltEvent);
            log.info("Saved dead letter event record with id {}", dltEvent.getId());
        } catch (Exception e) {
            log.error("Failed to persist dead letter event record", e);
        }

        if (ack != null) ack.acknowledge();
    }
}
