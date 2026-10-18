package com.lky.kaipay.payment.service.acquirer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SimulatedAuthorizationOperationRepository extends JpaRepository<SimulatedAuthorizationOperation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM SimulatedAuthorizationOperation o WHERE o.paymentId = :paymentId")
    Optional<SimulatedAuthorizationOperation> findByIdForUpdate(@Param("paymentId") UUID paymentId);

    long countByDispositionIn(java.util.Collection<SimulatedAuthorizationOperation.Disposition> dispositions);
}
