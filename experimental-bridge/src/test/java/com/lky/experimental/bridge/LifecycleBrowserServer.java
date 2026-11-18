package com.lky.experimental.bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/** Test-classpath-only clock control over process stdin. Never packaged into the runtime jar. */
public final class LifecycleBrowserServer {
    private static final ControlledClock CLOCK = new ControlledClock();

    public static void main(String[] args) throws Exception {
        try (var context = new SpringApplicationBuilder(BridgeApplication.class, ClockConfiguration.class).run(args);
             var commands = new BufferedReader(new InputStreamReader(System.in))) {
            System.out.println("B5_READY");
            for (String command; (command = commands.readLine()) != null;) {
                if (command.equals("STOP")) break;
                if (!command.startsWith("TIME ")) throw new IllegalArgumentException("TEST_COMMAND_INVALID");
                CLOCK.now = Instant.parse(command.substring(5));
                System.out.println("B5_CLOCK " + CLOCK.now);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary ObservationService controlledObservations(SnapshotStore store, BridgeAccess access, Environment environment) {
            // Same mode inputs as the production bean, so a harness run reaches every mode with a controlled clock.
            return new ObservationService(store, CLOCK, access.deviceId,
                    environment.getProperty("bridge.review-period-seconds", Integer.class, 1800), access.integrationEnabled(), access.observedAmountMinor);
        }
    }

    private static final class ControlledClock extends Clock {
        volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
