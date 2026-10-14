package com.lky.kaipay.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAdmissionUnitTest {
    private final AtomicLong time = new AtomicLong();
    private LoginAdmission limiter(int peer, int total, int concurrent, int capacity) {
        return new LoginAdmission(peer, total, concurrent, capacity, Duration.ofMinutes(1), time::get);
    }
    private void admit(LoginAdmission limiter, String peer) {
        var decision = limiter.acquire(peer);
        assertThat(decision.permit()).isNotNull();
        decision.permit().close();
    }
    @Test void attemptsStayChargedAndRejectionsDoNotExtendExpiry() {
        var limiter = limiter(2, 10, 2, 4);
        admit(limiter, "a"); admit(limiter, "a");
        assertThat(limiter.acquire("a").retryAfterSeconds()).isEqualTo(60);
        time.set(Duration.ofSeconds(59).toNanos());
        assertThat(limiter.acquire("a").retryAfterSeconds()).isEqualTo(1);
        time.set(Duration.ofMinutes(1).toNanos());
        admit(limiter, "a");
    }
    @Test void anotherPeerCanUseSameAccountAndRotatingPeersCannotEvadeGlobalBudget() {
        // Account names never enter the API or state. Peer saturation has no account lockout.
        var limiter = limiter(1, 3, 2, 4);
        admit(limiter, "a"); assertThat(limiter.acquire("a").permit()).isNull();
        admit(limiter, "b"); admit(limiter, "c");
        assertThat(limiter.acquire("d").permit()).isNull();
        assertThat(limiter.peerCount()).isEqualTo(3);
        time.set(Duration.ofMinutes(1).toNanos());
        admit(limiter, "d");
        assertThat(limiter.peerCount()).isEqualTo(1);
    }
    @Test void boundedPeerTableNeverEvictsActiveBudgetsAndRecovers() {
        var limiter = limiter(1, 10, 2, 2);
        admit(limiter, "a");
        time.set(Duration.ofSeconds(20).toNanos()); admit(limiter, "b");
        for (int i = 0; i < 10000; i++) assertThat(limiter.acquire("rotated-" + i).permit()).isNull();
        assertThat(limiter.peerCount()).isEqualTo(2);
        assertThat(limiter.acquire("a").permit()).isNull();
        assertThat(limiter.acquire("c").retryAfterSeconds()).isEqualTo(40);
        time.set(Duration.ofMinutes(1).toNanos()); admit(limiter, "c");
        assertThat(limiter.peerCount()).isEqualTo(2);
        assertThat(limiter.acquire("b").permit()).isNull();
    }
    @Test void competingRequestsCannotOverAdmitAndPermitsAreIdempotent() throws Exception {
        var limiter = limiter(100, 100, 4, 32);
        try (var executor = Executors.newFixedThreadPool(20)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<LoginAdmission.Decision>>();
            for (int i = 0; i < 20; i++) {
                final int peer = i;
                futures.add(executor.submit(() -> { start.await(); return limiter.acquire("peer-" + peer); }));
            }
            start.countDown();
            var decisions = new ArrayList<LoginAdmission.Decision>();
            for (var future : futures) decisions.add(future.get());
            var admitted = decisions.stream().filter(d -> d.permit() != null).toList();
            assertThat(admitted).hasSize(4);
            assertThat(limiter.peerCount()).isEqualTo(4);
            var released = admitted.getFirst().permit(); released.close(); released.close();
            var replacement = limiter.acquire("new"); assertThat(replacement.permit()).isNotNull();
            assertThat(limiter.acquire("overflow").permit()).isNull();
            replacement.permit().close(); admitted.forEach(d -> d.permit().close());
            admit(limiter, "recovered");
        }
    }
    @Test void concurrentBudgetCannotResetWithRateWindow() {
        var limiter = limiter(2, 10, 1, 4);
        var held = limiter.acquire("a").permit();
        time.set(Duration.ofHours(1).toNanos());
        assertThat(limiter.acquire("b").permit()).isNull();
        held.close(); admit(limiter, "b");
    }
    @Test void competingRequestsCannotOverrunPeerOrTotalStartBudgets() throws Exception {
        for (boolean samePeer : new boolean[]{true, false}) {
            var limiter = samePeer ? limiter(5,50,32,32) : limiter(1,5,32,32);
            try (var executor = Executors.newFixedThreadPool(20)) {
                var start = new CountDownLatch(1);
                var futures = new ArrayList<Future<LoginAdmission.Decision>>();
                for (int i=0;i<20;i++) {
                    final String peer = samePeer ? "shared-peer" : "peer-"+i;
                    futures.add(executor.submit(() -> { start.await(); return limiter.acquire(peer); }));
                }
                start.countDown();
                var permits = new ArrayList<LoginAdmission.Permit>();
                for (var future : futures) {
                    var permit = future.get().permit();
                    if (permit != null) permits.add(permit);
                }
                assertThat(permits).hasSize(5);
                permits.forEach(LoginAdmission.Permit::close);
            }
        }
    }
    @Test void productionBeanDefaultsEnforceTenStartsAndRejectInvalidOverrides() {
        var configuration = new SecurityConfiguration();
        var limiter = configuration.loginAdmission(new org.springframework.mock.env.MockEnvironment());
        for (int i=0;i<10;i++) admit(limiter,"production-peer");
        assertThat(limiter.acquire("production-peer").permit()).isNull();
        admit(limiter,"other-peer");
        var disabled = new org.springframework.mock.env.MockEnvironment().withProperty("kaipay.security.login.peer-attempts","0");
        assertThatThrownBy(() -> configuration.loginAdmission(disabled)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidConfigurationAndPeerInputFailClosed() {
        for (int[] bounds : new int[][]{{0,10,1,1},{11,10,1,1},{1,10,0,1},{1,10,33,1},{1,10,1,0},{1,10,1,65537}})
            assertThatThrownBy(() -> limiter(bounds[0],bounds[1],bounds[2],bounds[3])).isInstanceOf(IllegalArgumentException.class);
        for (var window : new Duration[]{Duration.ZERO,Duration.ofHours(2)})
            assertThatThrownBy(() -> new LoginAdmission(1,10,1,1,window)).isInstanceOf(IllegalArgumentException.class);
        var limiter = limiter(1,1,1,1);
        for (String peer : new String[]{null,"","x".repeat(65)}) assertThat(limiter.acquire(peer).permit()).isNull();
        assertThat(limiter.peerCount()).isZero(); admit(limiter,"a");
    }
}
