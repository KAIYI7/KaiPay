package com.lky.kaipay.common.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.ConsumerFailedToStartEvent;
import org.springframework.kafka.event.ConsumerStartedEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.event.NonResponsiveConsumerEvent;
import org.springframework.stereotype.Component;

/** Safe lifecycle signals replace framework messages that can render records/exceptions. */
@Component
@Slf4j
public class KafkaLifecycleDiagnostics {
    @EventListener public void started(ConsumerStartedEvent event) {
        log.info("Kafka consumer started");
    }
    @EventListener public void failedToStart(ConsumerFailedToStartEvent event) {
        log.error("Kafka consumer failed to start");
    }
    @EventListener public void nonResponsive(NonResponsiveConsumerEvent event) {
        log.error("Kafka consumer poll nonresponsive");
    }
    @EventListener public void stopped(ConsumerStoppedEvent event) {
        if (event.getReason() == ConsumerStoppedEvent.Reason.NORMAL) {
            log.info("Kafka consumer stopped: reason=NORMAL");
        } else {
            log.error("Kafka consumer stopped: reason={}", event.getReason() == null ? "UNKNOWN" : event.getReason().name());
        }
    }
}
