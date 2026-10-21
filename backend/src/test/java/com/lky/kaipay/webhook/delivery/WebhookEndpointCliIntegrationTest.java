package com.lky.kaipay.webhook.delivery;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class WebhookEndpointCliIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @TempDir Path root;
    UUID merchant;
    @BeforeEach void setup()throws Exception{
        merchant=UUID.randomUUID();jdbc.update("INSERT INTO merchants(id,name,api_key_hash,status)VALUES(?,'Operator fixture',?,'ACTIVE')",merchant,merchant.toString());
        WebhookSecretsIntegrationTest.restrict(root);
        for(String alias:List.of("first","second")){byte[] material=new byte[32];new java.security.SecureRandom().nextBytes(material);Path file=root.resolve(alias);Files.write(file,material);WebhookSecretsIntegrationTest.restrict(file);}
    }
    @AfterEach void cleanup(){jdbc.update("DELETE FROM merchant_webhook_endpoints WHERE merchant_id=?",merchant);jdbc.update("DELETE FROM merchants WHERE id=?",merchant);}
    @Test void offlineEnrollmentRejectsUnsafeConfigAndRetiresEvenAfterMerchantDisable()throws Exception{
        assertThat(run("enroll",merchant.toString(),"operator_fixture","TEST","https://receiver.example.test/hook","8.8.8.8",UUID.randomUUID().toString(),"first")).isZero();
        UUID endpoint=jdbc.queryForObject("SELECT id FROM merchant_webhook_endpoints WHERE merchant_id=?",UUID.class,merchant);
        jdbc.update("UPDATE merchants SET status='INACTIVE' WHERE id=?",merchant);
        assertThat(run("retire",endpoint.toString(),"operator_fixture","ROTATION")).isZero();
        assertThat(jdbc.queryForMap("SELECT state,retired_by,retirement_reason_code FROM merchant_webhook_endpoints WHERE id=?",endpoint))
                .containsEntry("state","RETIRED").containsEntry("retired_by","operator_fixture").containsEntry("retirement_reason_code","ROTATION");
        jdbc.update("UPDATE merchants SET status='ACTIVE' WHERE id=?",merchant);
        assertThat(run("enroll",merchant.toString(),"operator_fixture","TEST","https://receiver.example.test/hook?secret=x","127.0.0.1",UUID.randomUUID().toString(),"second")).isNotZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_webhook_endpoints WHERE merchant_id=?",Long.class,merchant)).isEqualTo(1);
        assertThat(run("enroll",merchant.toString(),"operator_fixture","TEST","https://receiver.example.test/hook","8.8.8.8",UUID.randomUUID().toString(),"second")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_webhook_deliveries WHERE merchant_id=?",Long.class,merchant)).isZero();
    }
    private int run(String... args)throws Exception{
        var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),WebhookEndpointCli.class.getName()));command.addAll(List.of(args));
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("operator-result.log").toFile());
        builder.environment().put("DB_URL",postgres.getJdbcUrl());builder.environment().put("DB_USERNAME",postgres.getUsername());builder.environment().put("DB_PASSWORD",postgres.getPassword());builder.environment().put("WEBHOOK_SECRET_DIRECTORY",root.toString());
        Process process=builder.start();try{assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();return process.exitValue();}finally{if(process.isAlive())process.destroyForcibly();}
    }
}
