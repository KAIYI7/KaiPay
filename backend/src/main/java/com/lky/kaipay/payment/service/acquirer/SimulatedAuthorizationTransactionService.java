package com.lky.kaipay.payment.service.acquirer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The local simulated provider's acceptance boundary; never changes payment state. */
@Service
public class SimulatedAuthorizationTransactionService {
    private final SimulatedAuthorizationOperationRepository operations;

    public SimulatedAuthorizationTransactionService(SimulatedAuthorizationOperationRepository operations) {
        this.operations = operations;
    }

    public record Evaluation(AcquirerAuthorizationResult result, long evaluationCount) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Evaluation evaluate(AuthorizationRequest request) {
        var operation = operations.findByIdForUpdate(request.paymentId())
                .orElseThrow(() -> new IllegalArgumentException("Missing simulated authorization intent"));
        operation.validateRequest(request);
        operation.evaluate();
        operations.flush();
        return new Evaluation(operation.getResult(), operation.getEvaluationCount());
    }
}
