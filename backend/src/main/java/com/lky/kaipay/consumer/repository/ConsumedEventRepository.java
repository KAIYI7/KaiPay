package com.lky.kaipay.consumer.repository;

import com.lky.kaipay.consumer.domain.ConsumedEvent;
import com.lky.kaipay.consumer.domain.ConsumedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, ConsumedEventId> {

    boolean existsByIdEventIdAndIdConsumerGroup(UUID eventId, String consumerGroup);

    Optional<ConsumedEvent> findByIdEventIdAndIdConsumerGroup(UUID eventId, String consumerGroup);

    List<ConsumedEvent> findByPaymentIdOrderByProcessedAtAsc(UUID paymentId);
}
