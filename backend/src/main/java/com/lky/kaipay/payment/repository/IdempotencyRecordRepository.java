package com.lky.kaipay.payment.repository;

import com.lky.kaipay.payment.domain.IdempotencyRecord;
import com.lky.kaipay.payment.domain.IdempotencyRecordId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecordId> {

    @Query("SELECT r FROM IdempotencyRecord r WHERE r.id.merchantId = :merchantId AND r.id.idempotencyKey = :key")
    Optional<IdempotencyRecord> findByMerchantIdAndKey(@Param("merchantId") UUID merchantId, @Param("key") String key);

    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :now")
    int deleteExpiredRecords(@Param("now") Instant now);
}
