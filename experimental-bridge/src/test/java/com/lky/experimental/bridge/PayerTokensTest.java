package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayerTokensTest {
    private final MutableClock clock = new MutableClock();
    private long nanos;
    private final PayerTokens tokens = new PayerTokens(clock, () -> nanos);

    @Test void randomRequestBoundTokensAreCompatibleAndRestartRevokesThem() {
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        String first = tokens.issue(one), second = tokens.issue(two);
        assertThat(first.matches("[A-Za-z0-9_-]{43}")).isTrue();
        assertThat(first.equals(second)).isFalse();
        assertThat(authorized(tokens, one, first)).isTrue();
        assertThat(authorized(tokens, two, first)).isFalse();
        assertThat(authorized(tokens, one, second)).isFalse();
        assertThat(authorized(new PayerTokens(clock), one, first)).isFalse();
        assertThat(tokens.authorized(one, null)).isFalse();
        assertThat(tokens.authorized(one, "Bearer short")).isFalse();
        assertThat(tokens.authorized(one, "Basic " + first)).isFalse();
    }

    @Test void reissueRotatesAndRevocationIsIdempotentAndRequestBound() {
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        String original = tokens.issue(one), unrelated = tokens.issue(two);
        String replacement = tokens.issue(one);
        assertThat(original.equals(replacement)).isFalse();
        assertThat(authorized(tokens, one, original)).isFalse();
        assertThat(authorized(tokens, one, replacement)).isTrue();
        tokens.revoke(one); tokens.revoke(one);
        assertThat(authorized(tokens, one, replacement)).isFalse();
        assertThat(authorized(tokens, two, unrelated)).isTrue();
        String renewed = tokens.issue(one);
        assertThat(authorized(tokens, one, renewed)).isTrue();
        assertThat(authorized(tokens, one, original)).isFalse();
        assertThat(authorized(tokens, one, replacement)).isFalse();
    }

    @Test void exactWallExpiryDoesNotSlideOnReadsOrResurrectOnRollback() {
        UUID id = UUID.randomUUID(); String token = tokens.issue(id);
        clock.now = clock.now.plus(PayerTokens.LIFETIME).minusNanos(1);
        assertThat(authorized(tokens, id, token)).isTrue();
        clock.now = clock.now.plusNanos(1);
        assertThat(authorized(tokens, id, token)).isFalse();
        clock.now = clock.now.minus(PayerTokens.LIFETIME);
        assertThat(authorized(tokens, id, token)).isFalse();
    }

    @Test void monotonicExpiryBoundsLifetimeEvenWhenWallClockRollsBackBeforeExpiry() {
        UUID id = UUID.randomUUID(); String token = tokens.issue(id);
        clock.now = clock.now.minusSeconds(3600);
        nanos = PayerTokens.LIFETIME.toNanos() - 1;
        assertThat(authorized(tokens, id, token)).isTrue();
        nanos++;
        assertThat(authorized(tokens, id, token)).isFalse();
        clock.now = clock.now.minusSeconds(3600);
        assertThat(authorized(tokens, id, token)).isFalse();
    }

    @Test void reissueStartsANewFixedLifetime() {
        UUID id = UUID.randomUUID(); String original = tokens.issue(id);
        clock.now = clock.now.plusSeconds(899); nanos += 899_000_000_000L;
        String next = tokens.issue(id);
        clock.now = clock.now.plusSeconds(1); nanos += 1_000_000_000L;
        assertThat(authorized(tokens, id, original)).isFalse();
        assertThat(authorized(tokens, id, next)).isTrue();
        clock.now = clock.now.plusSeconds(899); nanos += 899_000_000_000L;
        assertThat(authorized(tokens, id, next)).isFalse();
    }

    @Test void capacityFailsClosedButAllowsRotationRevocationAndExpiredEntryReclamation() {
        UUID first = UUID.randomUUID(); String original = tokens.issue(first);
        for (int i = 1; i < PayerTokens.MAX_GRANTS; i++) tokens.issue(UUID.randomUUID());
        UUID overflow = UUID.randomUUID();
        assertThatThrownBy(() -> tokens.issue(overflow)).isInstanceOf(BridgeFailure.class)
                .hasMessage("PAYER_ACCESS_CAPACITY");
        assertThat(authorized(tokens, first, original)).isTrue();
        String rotated = tokens.issue(first);
        assertThat(authorized(tokens, first, original)).isFalse();
        assertThat(authorized(tokens, first, rotated)).isTrue();
        tokens.revoke(first);
        String admitted = tokens.issue(overflow);
        assertThat(authorized(tokens, overflow, admitted)).isTrue();
        clock.now = clock.now.plus(PayerTokens.LIFETIME);
        UUID afterExpiry = UUID.randomUUID(); String fresh = tokens.issue(afterExpiry);
        clock.now = clock.now.minus(PayerTokens.LIFETIME);
        assertThat(authorized(tokens, overflow, admitted)).isFalse();
        assertThat(authorized(tokens, afterExpiry, fresh)).isTrue();
    }

    private boolean authorized(PayerTokens target, UUID id, String token) {
        return target.authorized(id, "Bearer " + token);
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
