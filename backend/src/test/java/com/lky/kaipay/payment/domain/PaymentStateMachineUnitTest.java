package com.lky.kaipay.payment.domain;

import com.lky.kaipay.common.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Payment State Machine Unit Tests")
class PaymentStateMachineUnitTest {

    @Nested
    @DisplayName("Allowed Transitions")
    class AllowedTransitions {

        @Test
        @DisplayName("CREATED -> PROCESSING is allowed")
        void createdToProcessing() {
            assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.PROCESSING)).isTrue();
        }

        @Test
        @DisplayName("CREATED -> FAILED is allowed")
        void createdToFailed() {
            assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        }

        @Test
        @DisplayName("PROCESSING -> AUTHORIZED is allowed")
        void processingToAuthorized() {
            assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.AUTHORIZED)).isTrue();
        }

        @Test
        @DisplayName("PROCESSING -> DECLINED is allowed")
        void processingToDeclined() {
            assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.DECLINED)).isTrue();
        }

        @Test
        @DisplayName("PROCESSING -> FAILED is allowed")
        void processingToFailed() {
            assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        }

        @Test
        @DisplayName("AUTHORIZED -> CAPTURED is allowed")
        void authorizedToCaptured() {
            assertThat(PaymentStatus.AUTHORIZED.canTransitionTo(PaymentStatus.CAPTURED)).isTrue();
        }

        @Test
        @DisplayName("CAPTURED -> REFUND_PENDING, PARTIALLY_REFUNDED, and REFUNDED are allowed")
        void capturedToRefund() {
            assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.REFUND_PENDING)).isTrue();
            assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED)).isTrue();
            assertThat(PaymentStatus.CAPTURED.canTransitionTo(PaymentStatus.REFUNDED)).isTrue();
        }

        @Test
        @DisplayName("PARTIALLY_REFUNDED -> PARTIALLY_REFUNDED and REFUNDED are allowed")
        void partiallyRefundedTransitions() {
            assertThat(PaymentStatus.PARTIALLY_REFUNDED.canTransitionTo(PaymentStatus.PARTIALLY_REFUNDED)).isTrue();
            assertThat(PaymentStatus.PARTIALLY_REFUNDED.canTransitionTo(PaymentStatus.REFUNDED)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(PaymentStatus.class)
        @DisplayName("Self transitions (idempotent no-op) are always allowed")
        void selfTransitionIsAllowed(PaymentStatus status) {
            assertThat(status.canTransitionTo(status)).isTrue();
        }
    }

    @Nested
    @DisplayName("Disallowed Transitions")
    class DisallowedTransitions {

        @Test
        @DisplayName("CREATED -> CAPTURED directly is forbidden")
        void createdToCapturedDirectlyIsForbidden() {
            assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
        }

        @Test
        @DisplayName("CREATED -> REFUNDED directly is forbidden")
        void createdToRefundedDirectlyIsForbidden() {
            assertThat(PaymentStatus.CREATED.canTransitionTo(PaymentStatus.REFUNDED)).isFalse();
        }

        @Test
        @DisplayName("FAILED is terminal and cannot transition to any other status")
        void failedCannotTransition() {
            assertThat(PaymentStatus.FAILED.isTerminal()).isTrue();
            assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.PROCESSING)).isFalse();
            assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.AUTHORIZED)).isFalse();
        }

        @Test
        @DisplayName("DECLINED is terminal and cannot transition to any other status")
        void declinedCannotTransition() {
            assertThat(PaymentStatus.DECLINED.isTerminal()).isTrue();
            assertThat(PaymentStatus.DECLINED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
        }

        @Test
        @DisplayName("REFUNDED is terminal and cannot transition to any other status")
        void refundedCannotTransition() {
            assertThat(PaymentStatus.REFUNDED.isTerminal()).isTrue();
            assertThat(PaymentStatus.REFUNDED.canTransitionTo(PaymentStatus.CAPTURED)).isFalse();
        }
    }

    @Nested
    @DisplayName("Payment Entity Transition Enforcement")
    class EntityTransitionEnforcement {

        @Test
        @DisplayName("Payment entity transition throws exception on illegal state transition")
        void paymentEntityThrowsOnIllegalTransition() {
            Payment payment = Payment.builder()
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.CREATED)
                    .idempotencyKey("key-1")
                    .build();

            assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.REFUNDED))
                    .isInstanceOf(InvalidStateTransitionException.class)
                    .hasMessageContaining("Invalid state transition from CREATED to REFUNDED");
        }

        @Test
        @DisplayName("Payment entity transitions successfully on legal paths")
        void paymentEntityTransitionsSuccessfully() {
            Payment payment = Payment.builder()
                    .amountCents(5000L)
                    .currency("USD")
                    .status(PaymentStatus.CREATED)
                    .idempotencyKey("key-1")
                    .build();

            assertThatCode(() -> {
                payment.transitionTo(PaymentStatus.PROCESSING);
                payment.markAuthorized("gw-ref-123");
                payment.markCaptured();
                payment.markRefunded();
            }).doesNotThrowAnyException();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(payment.getGatewayReference()).isEqualTo("gw-ref-123");
        }
    }
}
