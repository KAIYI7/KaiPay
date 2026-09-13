package com.lky.kaipay.consumer.service;

import com.lky.kaipay.consumer.domain.ConsumedEvent;
import com.lky.kaipay.consumer.domain.ConsumedEventId;
import com.lky.kaipay.consumer.repository.ConsumedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumerDeduplicationService {

    private final ConsumedEventRepository consumedEventRepository;

    @Transactional(readOnly = true)
    public boolean isEventConsumed(UUID eventId, String consumerGroup) {
        log.debug("Checking deduplication for eventId: {}, consumerGroup: {}", eventId, consumerGroup);
        return consumedEventRepository.existsByIdEventIdAndIdConsumerGroup(eventId, consumerGroup);
    }

    @Transactional
    public ConsumedEvent recordConsumed(UUID eventId, String consumerGroup, UUID paymentId, String eventType, String status) {
        log.info("Recording consumed event: eventId={}, consumerGroup={}, paymentId={}, eventType={}, status={}",
                eventId, consumerGroup, paymentId, eventType, status);

        ConsumedEventId id = ConsumedEventId.builder()
                .eventId(eventId)
                .consumerGroup(consumerGroup)
                .build();

        ConsumedEvent consumedEvent = ConsumedEvent.builder()
                .id(id)
                .paymentId(paymentId)
                .eventType(eventType)
                .status(status)
                .build();

        return consumedEventRepository.save(consumedEvent);
    }
}
