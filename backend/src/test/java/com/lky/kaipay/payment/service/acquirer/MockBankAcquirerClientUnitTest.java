package com.lky.kaipay.payment.service.acquirer;

import com.lky.kaipay.common.exception.GatewayTimeoutException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockBankAcquirerClientUnitTest {
    @Test void rejectsInvalidCanonicalRequests() {
        UUID payment = UUID.randomUUID(), merchant = UUID.randomUUID();
        assertThatThrownBy(() -> new AuthorizationRequest(payment, merchant, 0, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthorizationRequest(payment, merchant, 1, "usd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthorizationRequest(null, merchant, 1, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthorizationRequest(payment, null, 1, "USD")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void immutableOutcomeAndRequestSurviveEquivalentReplay() {
        var request = request(5000);
        var operation = SimulatedAuthorizationOperation.intent(request);
        operation.evaluate();
        var result = operation.getResult();
        operation.validateRequest(request);
        operation.evaluate();
        assertThat(operation.getRequest()).isEqualTo(request);
        assertThat(operation.getResult()).isEqualTo(result);
        assertThat(operation.getEvaluationCount()).isEqualTo(1);
        assertThatThrownBy(() -> operation.validateRequest(new AuthorizationRequest(request.paymentId(), UUID.randomUUID(), 5000, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operation.validateRequest(new AuthorizationRequest(request.paymentId(), request.merchantId(), 5001, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operation.validateRequest(new AuthorizationRequest(request.paymentId(), request.merchantId(), 5000, "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void timeoutProgressionCommitsCountsBeforeRecovering() {
        var operation = SimulatedAuthorizationOperation.intent(request(888800));
        operation.evaluate();
        assertThat(operation.getResult()).isNull();
        assertThat(operation.getEvaluationCount()).isEqualTo(1);
        operation.evaluate();
        assertThat(operation.getResult()).isNull();
        operation.evaluate();
        assertThat(operation.getResult().getAuthorizationCode()).startsWith("AUTH-RECOVERED-");
        var accepted = operation.getResult();
        operation.evaluate();
        assertThat(operation.getEvaluationCount()).isEqualTo(3);
        assertThat(operation.getResult()).isEqualTo(accepted);
    }

    @Test void unknownAndDefinitiveRejectionAreDifferentOutcomes() {
        var unknown = SimulatedAuthorizationOperation.intent(request(777700));
        unknown.evaluate();
        unknown.evaluate();
        assertThat(unknown.getDisposition()).isEqualTo(SimulatedAuthorizationOperation.Disposition.UNRESOLVED);
        assertThat(unknown.getEvaluationCount()).isEqualTo(2);
        assertThat(unknown.getResultAt()).isNull();
        var rejected = SimulatedAuthorizationOperation.intent(request(666600));
        rejected.evaluate();
        assertThat(rejected.getResult().isRejectedNoAcceptance()).isTrue();
        assertThat(rejected.getResult().isApproved()).isFalse();
        assertThat(rejected.getResult().getFailureCode()).isEqualTo(SimulatedAuthorizationOperation.REJECTION_CODE);
        assertThat(rejected.getResult().getDeclineCode()).isNull();
        rejected.evaluate();
        assertThat(rejected.getEvaluationCount()).isEqualTo(1);
    }

    @Test void declineIsDurableDataRatherThanAThrownFailure() {
        var operation = SimulatedAuthorizationOperation.intent(request(999900));
        operation.evaluate();
        assertThat(operation.getResult().isApproved()).isFalse();
        assertThat(operation.getResult().isRejectedNoAcceptance()).isFalse();
        assertThat(operation.getResult().getDeclineCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test void wrapperThrowsOnlyAfterIndependentTransactionReturnsUnresolved() {
        var simulator = mock(SimulatedAuthorizationTransactionService.class);
        var repository = mock(SimulatedAuthorizationOperationRepository.class);
        var request = request(777700);
        when(simulator.evaluate(request)).thenReturn(new SimulatedAuthorizationTransactionService.Evaluation(null, 2));
        var client = new MockBankAcquirerClient(simulator, repository);
        assertThatThrownBy(() -> client.authorize(request)).isInstanceOf(GatewayTimeoutException.class).hasMessageContaining("attempt 2");
        verify(simulator).evaluate(request);
        verifyNoInteractions(repository);
    }

    private AuthorizationRequest request(long amount) {
        return new AuthorizationRequest(UUID.randomUUID(), UUID.randomUUID(), amount, "USD");
    }
}
