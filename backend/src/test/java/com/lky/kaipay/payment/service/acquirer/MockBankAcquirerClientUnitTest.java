package com.lky.kaipay.payment.service.acquirer;

import com.lky.kaipay.common.exception.GatewayTimeoutException;
import com.lky.kaipay.common.exception.NonRetryableGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MockBankAcquirerClient Unit Tests")
class MockBankAcquirerClientUnitTest {

    private MockBankAcquirerClient client;

    @BeforeEach
    void setUp() {
        client = new MockBankAcquirerClient();
        client.clearLedger();
    }

    @Test
    @DisplayName("Should simulate transient timeout on attempts 1 & 2 and recover on attempt 3 for $8,888.00")
    void testAuthorize_TransientTimeout_RecoversOnThirdAttempt() {
        UUID paymentId = UUID.randomUUID();
        long amountCents = 888800L;
        String currency = "USD";

        // Attempt 1: Transient timeout
        assertThatThrownBy(() -> client.authorize(paymentId, amountCents, currency))
                .isInstanceOf(GatewayTimeoutException.class)
                .hasMessageContaining("attempt 1");

        // Attempt 2: Transient timeout
        assertThatThrownBy(() -> client.authorize(paymentId, amountCents, currency))
                .isInstanceOf(GatewayTimeoutException.class)
                .hasMessageContaining("attempt 2");

        // Attempt 3: Recovery and approval
        AcquirerAuthorizationResult result = client.authorize(paymentId, amountCents, currency);
        assertThat(result).isNotNull();
        assertThat(result.isApproved()).isTrue();
        assertThat(result.getAuthorizationCode()).startsWith("AUTH-RECOVERED-");
        assertThat(result.getDeclineCode()).isNull();
        assertThat(result.getDeclineMessage()).isNull();
        assertThat(result.getTimestamp()).isNotNull();

        // Attempt 4: Idempotent replay of recovered authorization
        AcquirerAuthorizationResult replayResult = client.authorize(paymentId, amountCents, currency);
        assertThat(replayResult.isApproved()).isTrue();
        assertThat(replayResult.getAuthorizationCode()).isEqualTo(result.getAuthorizationCode());

        assertThat(client.getExecutionCount(paymentId)).isEqualTo(4);
        assertThat(client.getUniqueAuthorizationsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should continuously throw GatewayTimeoutException on all attempts for $7,777.00")
    void testAuthorize_PersistentTimeout_ThrowsOnAllAttempts() {
        UUID paymentId = UUID.randomUUID();
        long amountCents = 777700L;
        String currency = "USD";

        // Attempt 1
        assertThatThrownBy(() -> client.authorize(paymentId, amountCents, currency))
                .isInstanceOf(GatewayTimeoutException.class)
                .hasMessageContaining("attempt 1");

        // Attempt 2
        assertThatThrownBy(() -> client.authorize(paymentId, amountCents, currency))
                .isInstanceOf(GatewayTimeoutException.class)
                .hasMessageContaining("attempt 2");

        // Attempt 3
        assertThatThrownBy(() -> client.authorize(paymentId, amountCents, currency))
                .isInstanceOf(GatewayTimeoutException.class)
                .hasMessageContaining("attempt 3");

        // Attempt 4
        assertThatThrownBy(() -> client.authorize(paymentId, amountCents, currency))
                .isInstanceOf(GatewayTimeoutException.class)
                .hasMessageContaining("attempt 4");

        assertThat(client.getExecutionCount(paymentId)).isEqualTo(4);
        assertThat(client.getUniqueAuthorizationsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should throw NonRetryableGatewayException for $6,666.00 fatal non-retryable error")
    void testAuthorize_NonRetryableFatalError_ThrowsNonRetryableException() {
        UUID paymentId = UUID.randomUUID();
        long amountCents = 666600L;
        String currency = "USD";

        assertThatThrownBy(() -> client.authorize(paymentId, amountCents, currency))
                .isInstanceOf(NonRetryableGatewayException.class)
                .hasMessageContaining("Simulated fatal non-retryable acquirer error");

        assertThat(client.getExecutionCount(paymentId)).isEqualTo(1);
        assertThat(client.getUniqueAuthorizationsCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should decline authorization when amount meets or exceeds trigger threshold 999900L ($9,999.00)")
    void testAuthorize_DeclineTrigger() {
        UUID paymentId = UUID.randomUUID();
        long amountCents = 999900L;
        String currency = "USD";

        AcquirerAuthorizationResult result = client.authorize(paymentId, amountCents, currency);

        assertThat(result).isNotNull();
        assertThat(result.isApproved()).isFalse();
        assertThat(result.getAuthorizationCode()).isNull();
        assertThat(result.getDeclineCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(result.getDeclineMessage()).isEqualTo("Simulated card decline: insufficient funds");
        assertThat(result.getTimestamp()).isNotNull();

        assertThat(client.getExecutionCount(paymentId)).isEqualTo(1);
        assertThat(client.getUniqueAuthorizationsCount()).isEqualTo(1);

        // Verify decline is also idempotent
        AcquirerAuthorizationResult replayedResult = client.authorize(paymentId, amountCents, currency);
        assertThat(replayedResult.isApproved()).isFalse();
        assertThat(replayedResult.getDeclineCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(client.getExecutionCount(paymentId)).isEqualTo(2);
        assertThat(client.getUniqueAuthorizationsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should generate authorization code and return approved result on default success amount")
    void testAuthorize_DefaultSuccess() {
        UUID paymentId = UUID.randomUUID();
        long amountCents = 5000L;
        String currency = "USD";

        AcquirerAuthorizationResult result = client.authorize(paymentId, amountCents, currency);

        assertThat(result).isNotNull();
        assertThat(result.isApproved()).isTrue();
        assertThat(result.getAuthorizationCode()).isNotNull().startsWith("AUTH-");
        assertThat(result.getDeclineCode()).isNull();
        assertThat(result.getDeclineMessage()).isNull();
        assertThat(result.getTimestamp()).isNotNull();

        assertThat(client.getExecutionCount(paymentId)).isEqualTo(1);
        assertThat(client.getUniqueAuthorizationsCount()).isEqualTo(1);

        // Idempotent replay
        AcquirerAuthorizationResult replayedResult = client.authorize(paymentId, amountCents, currency);
        assertThat(replayedResult.isApproved()).isTrue();
        assertThat(replayedResult.getAuthorizationCode()).isEqualTo(result.getAuthorizationCode());
        assertThat(client.getExecutionCount(paymentId)).isEqualTo(2);
        assertThat(client.getUniqueAuthorizationsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should clear ledger and execution counts when clearLedger is invoked")
    void testClearLedger() {
        UUID paymentId1 = UUID.randomUUID();
        UUID paymentId2 = UUID.randomUUID();

        client.authorize(paymentId1, 1000L, "USD");
        client.authorize(paymentId2, 2000L, "USD");

        assertThat(client.getUniqueAuthorizationsCount()).isEqualTo(2);
        assertThat(client.getExecutionCount(paymentId1)).isEqualTo(1);
        assertThat(client.getExecutionCount(paymentId2)).isEqualTo(1);

        client.clearLedger();

        assertThat(client.getUniqueAuthorizationsCount()).isEqualTo(0);
        assertThat(client.getExecutionCount(paymentId1)).isEqualTo(0);
        assertThat(client.getExecutionCount(paymentId2)).isEqualTo(0);
    }
}
