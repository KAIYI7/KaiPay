package com.lky.kaipay.ledger.repository;

import com.lky.kaipay.ledger.domain.Journal;
import com.lky.kaipay.ledger.domain.JournalSourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface JournalRepository extends JpaRepository<Journal, UUID> {

    Optional<Journal> findBySourceTypeAndSourceIdAndEventId(JournalSourceType sourceType, String sourceId, UUID eventId);

    Page<Journal> findByMerchantIdOrderByPostedAtDesc(UUID merchantId, Pageable pageable);

    Page<Journal> findAllByOrderByPostedAtDesc(Pageable pageable);
}
