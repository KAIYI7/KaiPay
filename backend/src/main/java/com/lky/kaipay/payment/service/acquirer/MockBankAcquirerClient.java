package com.lky.kaipay.payment.service.acquirer;

import com.lky.kaipay.common.exception.GatewayTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class MockBankAcquirerClient {
    private final SimulatedAuthorizationTransactionService simulator;
    private final SimulatedAuthorizationOperationRepository operations;

    public MockBankAcquirerClient(SimulatedAuthorizationTransactionService simulator,
                                  SimulatedAuthorizationOperationRepository operations) {
        this.simulator = simulator;
        this.operations = operations;
    }

    @Transactional(propagation = Propagation.NEVER)
    public AcquirerAuthorizationResult authorize(AuthorizationRequest request) {
        // The proxied call commits S before this method can throw a timeout or return a result.
        var evaluation = simulator.evaluate(request);
        log.info("Simulated gateway call for paymentId {}: durable evaluation attempt {}", request.paymentId(), evaluation.evaluationCount());
        if (evaluation.result() == null) {
            throw new GatewayTimeoutException("Simulated gateway timeout on attempt " + evaluation.evaluationCount());
        }
        return evaluation.result();
    }

    @Transactional(propagation = Propagation.NEVER, readOnly = true)
    public Optional<AcquirerAuthorizationResult> inquire(AuthorizationRequest request) {
        var operation = operations.findById(request.paymentId())
                .orElseThrow(() -> new IllegalArgumentException("Missing simulated authorization intent"));
        operation.validateRequest(request);
        return Optional.ofNullable(operation.getResult());
    }

    public long getEvaluationCount(UUID paymentId) {
        return operations.findById(paymentId).map(SimulatedAuthorizationOperation::getEvaluationCount).orElse(0L);
    }

    public long getUniqueAuthorizationsCount() {
        return operations.countByDispositionIn(List.of(SimulatedAuthorizationOperation.Disposition.APPROVED,
                SimulatedAuthorizationOperation.Disposition.DECLINED));
    }
}
