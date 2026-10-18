package com.lky.kaipay.consumer.repository;

import com.lky.kaipay.consumer.domain.ConsumedEvent;
import com.lky.kaipay.consumer.domain.ConsumedEventId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, ConsumedEventId> {
    @Modifying
    @Query(value = "INSERT INTO consumed_events(event_id, consumer_group, payment_id, event_type, status) "
            + "VALUES (:eventId, :groupId, :paymentId, :eventType, :status) "
            + "ON CONFLICT (event_id, consumer_group) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("groupId") String group,
                       @Param("paymentId") UUID paymentId, @Param("eventType") String eventType,
                       @Param("status") String status);


    boolean existsByIdEventIdAndIdConsumerGroup(UUID eventId, String consumerGroup);

    Optional<ConsumedEvent> findByIdEventIdAndIdConsumerGroup(UUID eventId, String consumerGroup);

    List<ConsumedEvent> findByPaymentIdOrderByProcessedAtAsc(UUID paymentId);
}
