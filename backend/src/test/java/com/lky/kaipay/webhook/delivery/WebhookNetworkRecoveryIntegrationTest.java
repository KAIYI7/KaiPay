package com.lky.kaipay.webhook.delivery;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class WebhookNetworkRecoveryIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired WebhookDeliveryStore store;
    @TempDir Path directory;
    SyntheticWebhookReceiver receiver;
    UUID customer,payment,endpoint,id,event;
    Instant occurred;
    @BeforeEach void setup()throws Exception{
        receiver=new SyntheticWebhookReceiver(directory);customer=UUID.randomUUID();payment=UUID.randomUUID();endpoint=UUID.randomUUID();id=UUID.randomUUID();event=UUID.randomUUID();
        occurred=Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("INSERT INTO merchants(id,name,api_key_hash,status)VALUES(?,'Network fixture',?,'ACTIVE')",receiver.merchant,receiver.merchant.toString());
        jdbc.update("INSERT INTO customers(id,merchant_id,email,full_name)VALUES(?,?,'fixture@example.test','Synthetic')",customer,receiver.merchant);
        jdbc.update("INSERT INTO payments(id,merchant_id,customer_id,amount_cents,currency,status,idempotency_key)VALUES(?,?,?,12345,'MYR','CAPTURED',?)",payment,receiver.merchant,customer,payment.toString());
        jdbc.update("""
                INSERT INTO merchant_webhook_endpoints(id,merchant_id,url,hostname,path,pins,key_id,secret_ref,state,created_by,reason_code)
                VALUES (?,?,?,?,'/webhook',ARRAY['127.0.0.1'],?,?,'ACTIVE','test','TEST')
                """,endpoint,receiver.merchant,"https://"+receiver.host+":"+receiver.port()+"/webhook",receiver.host,receiver.key,"fixture_"+endpoint);
        byte[] body=("{\"id\":\""+event+"\",\"merchant_id\":\""+receiver.merchant+"\",\"type\":\"payment.captured\",\"version\":1}").getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
                INSERT INTO merchant_webhook_deliveries(id,event_id,merchant_id,endpoint_id,notification_key,event_type,event_version,payment_id,occurred_at,payload,next_attempt_at,expires_at)
                VALUES(?,?,?,?,'capture:'||?::text,'payment.captured',1,?,?,?,?,?)
                """,id,event,receiver.merchant,endpoint,payment,payment,Timestamp.from(occurred),body,Timestamp.from(occurred),Timestamp.from(occurred.plusSeconds(86400)));
    }
    @AfterEach void cleanup()throws Exception{
        receiver.close();
        jdbc.update("UPDATE merchant_webhook_deliveries SET state='CANCELLED',current_attempt_id=NULL,claim_deadline=NULL,next_attempt_at=NULL,delivered_at=NULL WHERE id=?",id);
        jdbc.update("DELETE FROM merchant_webhook_attempts WHERE delivery_id=?",id);jdbc.update("DELETE FROM merchant_webhook_deliveries WHERE id=?",id);
        jdbc.update("DELETE FROM merchant_webhook_endpoints WHERE id=?",endpoint);jdbc.update("DELETE FROM payments WHERE id=?",payment);jdbc.update("DELETE FROM customers WHERE id=?",customer);jdbc.update("DELETE FROM merchants WHERE id=?",receiver.merchant);
    }
    @Test void actualChildJvmKilledAfterRemoteAcceptanceRecoversSameEventAndBytes()throws Exception{
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,WebhookCrashSender.class.getName(),receiver.certificate.toString(),Integer.toString(receiver.port()));
        builder.environment().put("FIXTURE_DB_URL",postgres.getJdbcUrl());builder.environment().put("FIXTURE_DB_USER",postgres.getUsername());builder.environment().put("FIXTURE_DB_PASSWORD",postgres.getPassword());
        Process child=builder.redirectErrorStream(true).start();
        try{
            child.getOutputStream().write((Base64.getEncoder().encodeToString(receiver.secret)+"\n").getBytes(StandardCharsets.US_ASCII));child.getOutputStream().close();
            try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()));var executor=Executors.newSingleThreadExecutor()){
                try {
                boolean accepted=executor.submit(()->{String line;while((line=reader.readLine())!=null)if(line.equals("ACCEPTED_BEFORE_COMPLETION"))return true;return false;}).get(20,TimeUnit.SECONDS);
                assertThat(accepted).isTrue();assertThat(receiver.receipts).containsKey(event);
                assertThat(jdbc.queryForObject("SELECT state FROM merchant_webhook_deliveries WHERE id=?",String.class,id)).isEqualTo("IN_FLIGHT");
                child.destroyForcibly();assertThat(child.waitFor(10,TimeUnit.SECONDS)).isTrue();
                } finally { if(child.isAlive()){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);} }
            }
        }finally{if(child.isAlive())child.destroyForcibly();}
        Timestamp deadline=jdbc.queryForObject("SELECT claim_deadline FROM merchant_webhook_deliveries WHERE id=?",Timestamp.class,id);
        store.claim(deadline.toInstant().plusMillis(1));
        Instant due=jdbc.queryForObject("SELECT next_attempt_at FROM merchant_webhook_deliveries WHERE id=?",Timestamp.class,id).toInstant();
        try(var transport=receiver.transport(Duration.ofSeconds(2));var secrets=new WebhookSecrets(receiver.key,receiver.merchant,endpoint,receiver.secret);
            var worker=new WebhookWorker(store,secrets,transport,Clock.fixed(due,ZoneOffset.UTC),true)){
            assertThat(worker.deliverOne()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT state FROM merchant_webhook_deliveries WHERE id=?",String.class,id)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForList("SELECT result FROM merchant_webhook_attempts WHERE delivery_id=? ORDER BY ordinal",String.class,id)).containsExactly("UNKNOWN","ACCEPTED");
        assertThat(java.nio.file.Files.readAllLines(receiver.journal)).hasSize(1);
        assertThat(receiver.requests.get()).isEqualTo(2);
    }
    @Test void isolatedScheduledHarnessDrivesActualTlsDeliveryWithoutFinancialChange()throws Exception{
        ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.initialize();
        CountDownLatch delivered=new CountDownLatch(1);receiver.mode.set(SyntheticWebhookReceiver.Mode.FAIL_FIRST);
        try(var transport=receiver.transport(Duration.ofSeconds(2));var secrets=new WebhookSecrets(receiver.key,receiver.merchant,endpoint,receiver.secret);
            var worker=new WebhookWorker(store,secrets,transport,Clock.systemUTC(),true)){
            var future=scheduler.scheduleWithFixedDelay(()->{worker.scheduledTick();if("DELIVERED".equals(jdbc.queryForObject("SELECT state FROM merchant_webhook_deliveries WHERE id=?",String.class,id)))delivered.countDown();},Duration.ofMillis(25));
            assertThat(delivered.await(10,TimeUnit.SECONDS)).isTrue();future.cancel(false);
            assertThat(receiver.receipts).containsKey(event);assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?",String.class,payment)).isEqualTo("CAPTURED");
            assertThat(jdbc.queryForList("SELECT result FROM merchant_webhook_attempts WHERE delivery_id=? ORDER BY ordinal",String.class,id)).containsExactly("HTTP_RETRYABLE","ACCEPTED");
        }finally{scheduler.shutdown();}
    }
}
