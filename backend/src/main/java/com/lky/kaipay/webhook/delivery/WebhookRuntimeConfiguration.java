package com.lky.kaipay.webhook.delivery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Clock;

@Configuration
public class WebhookRuntimeConfiguration {
    @Bean WebhookSecrets webhookSecrets(JdbcTemplate jdbc,Environment env){
        if(!"disabled".equals(env.getProperty("kaipay.webhooks.mode","disabled")))
            throw new IllegalStateException("Nonlocal webhook activation requires separate operational approval; local fixtures are test-only");
        return new WebhookSecrets(jdbc,env.getProperty("kaipay.webhooks.secret-directory",""));
    }
    @Bean WebhookWorker webhookWorker(WebhookDeliveryStore store,WebhookSecrets secrets){
        return new WebhookWorker(store,secrets,new PinnedWebhookTransport(),Clock.systemUTC());
    }
    @Bean(name="webhookScheduler") ThreadPoolTaskScheduler webhookScheduler(){return scheduler("webhook-worker-");}
    // Explicit default keeps existing outbox work off the webhook scheduler when Boot backs off auto-configuration.
    @Bean(name="taskScheduler") @org.springframework.context.annotation.Primary
    ThreadPoolTaskScheduler taskScheduler(){return scheduler("task-");}
    private static ThreadPoolTaskScheduler scheduler(String prefix){
        var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix(prefix);
        scheduler.setErrorHandler(e->org.slf4j.LoggerFactory.getLogger(WebhookRuntimeConfiguration.class)
                .error("Scheduled task failed: {}",com.lky.kaipay.common.observability.FailureDiagnostics.summary(e)));
        return scheduler;
    }
}
