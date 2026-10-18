package com.lky.kaipay.common.config;

import com.lky.kaipay.common.observability.FailureDiagnostics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Slf4j
public class SchedulingConfig {
    // Includes transaction completion failures outside the scheduled method body.
    @Bean ThreadPoolTaskSchedulerCustomizer safeScheduledFailures() {
        return scheduler -> scheduler.setErrorHandler(SchedulingConfig::reportFailure);
    }
    @Bean SimpleAsyncTaskSchedulerCustomizer safeAsyncScheduledFailures() {
        return scheduler -> scheduler.setErrorHandler(SchedulingConfig::reportFailure);
    }
    private static void reportFailure(Throwable failure) {
        log.error("Scheduled task failed: {}", FailureDiagnostics.summary(failure));
        // Repeating tasks retain the framework's default continue-after-failure behavior.
    }
}
