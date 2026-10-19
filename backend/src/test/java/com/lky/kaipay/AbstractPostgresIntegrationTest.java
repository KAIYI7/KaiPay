package com.lky.kaipay;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@ActiveProfiles({"test", "local"})
public abstract class AbstractPostgresIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.boot.resttestclient.TestRestTemplate securityHttp;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate securityJdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private com.fasterxml.jackson.databind.ObjectMapper securityMapper;

    protected com.lky.kaipay.security.SecurityHttpFixtures security() {
        return new com.lky.kaipay.security.SecurityHttpFixtures(securityHttp, securityJdbc, securityMapper);
    }

    @org.junit.jupiter.api.BeforeEach
    void clearIdentityFixtures() {
        securityJdbc.update("DELETE FROM demo_users");
        // Test-only fixture cleanup; runtime has no durable simulator reset API.
        securityJdbc.update("DELETE FROM simulated_authorization_operations");
    }

    protected static final PostgreSQLContainer postgres;

    @Container
    protected static final KafkaContainer KAFKA_CONTAINER;

    static {
        postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("kaipay_test")
                .withUsername("test_user")
                .withPassword("test_password");
        postgres.start();

        KAFKA_CONTAINER = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.2"));
        KAFKA_CONTAINER.start();
    }

    @DynamicPropertySource
    static void configureDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
        registry.add("kaipay.outbox.poll-interval-ms", () -> 3600000);
    }
}
