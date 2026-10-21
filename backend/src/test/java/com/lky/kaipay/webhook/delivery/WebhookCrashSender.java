package com.lky.kaipay.webhook.delivery;

import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.net.ssl.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Disposable test child, deliberately terminated after actual remote acknowledgement, before DB completion. */
public final class WebhookCrashSender {
    @org.springframework.boot.test.context.TestConfiguration @EnableTransactionManagement static class ChildConfig {
        @Bean JdbcTemplate jdbc(){var ds=new DriverManagerDataSource(System.getenv("FIXTURE_DB_URL"),System.getenv("FIXTURE_DB_USER"),System.getenv("FIXTURE_DB_PASSWORD"));return new JdbcTemplate(ds);}
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(JdbcTemplate jdbc){return new DataSourceTransactionManager(jdbc.getDataSource());}
        @Bean WebhookDeliveryStore store(JdbcTemplate jdbc){return new WebhookDeliveryStore(jdbc);}
    }
    public static void main(String[] args)throws Exception{
        // Test secrets enter via stdin, never process arguments or child stdout.
        var input=new BufferedReader(new InputStreamReader(System.in));byte[] secret=Base64.getDecoder().decode(input.readLine());
        try(var context=new AnnotationConfigApplicationContext(ChildConfig.class)){
            var store=context.getBean(WebhookDeliveryStore.class);var claim=store.claim(Instant.now()).orElseThrow();
            var certificate=java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(Files.newInputStream(Path.of(args[0])));
            var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);trust.setCertificateEntry("fixture",certificate);
            var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
            var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);
            try(var transport=SyntheticWebhookReceiver.fixtureTransport(Integer.parseInt(args[1]),claim.hostname(),ssl,Duration.ofSeconds(2))){
                if(!store.ready(claim,Instant.now()))throw new IllegalStateException("Child lost claim");
                var response=transport.send(claim,WebhookSigner.sign(secret,claim.keyId(),Instant.now().getEpochSecond(),claim.payload()));
                if(response.result()!=WebhookResult.ACCEPTED)throw new IllegalStateException("Fixture did not accept");
                System.out.println("ACCEPTED_BEFORE_COMPLETION");System.out.flush();
                Thread.sleep(30000); // parent forcibly terminates this actual JVM before any completion call
                throw new IllegalStateException("Parent did not terminate child");
            }
        }finally{Arrays.fill(secret,(byte)0);}
    }
}
