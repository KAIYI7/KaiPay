package com.lky.kaipay.outbox.repository;

import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentEventOutboxRepository extends JpaRepository<PaymentEventOutbox, UUID> {

    @Query(value = "SELECT * FROM payment_events_outbox WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<PaymentEventOutbox> findPendingEventsForUpdate(@Param("limit") int limit);

    Page<PaymentEventOutbox> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<PaymentEventOutbox> findByStatusOrderByCreatedAtDesc(PaymentEventOutboxStatus status, Pageable pageable);
}

