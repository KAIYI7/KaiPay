package com.lky.kaipay.dlt.repository;

import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    Page<DeadLetterEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<DeadLetterEvent> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    Page<DeadLetterEvent> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId, Pageable pageable);
}
