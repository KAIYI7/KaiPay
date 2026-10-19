package com.lky.kaipay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import com.lky.kaipay.payment.api.dto.CreatePaymentRequest;
import com.lky.kaipay.payment.service.IdempotencyService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Stored JSON evidence must retain its meaning across the framework migration. */
class PlatformCompatibilityIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdempotencyService idempotency;
    @Autowired PaymentEventOutboxRepository outbox;
    @Autowired RequestMappingHandlerAdapter mvc;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test void mvcAndHibernateUseTheExistingJsonContract() {
        var reader = mvc.getMessageConverters().stream()
                .filter(converter -> converter.canRead(CreatePaymentRequest.class, MediaType.APPLICATION_JSON))
                .findFirst().orElseThrow();
        assertThat(reader).isInstanceOf(MappingJackson2HttpMessageConverter.class);
        assertThat(((MappingJackson2HttpMessageConverter) reader).getObjectMapper()).isSameAs(mapper);
        var options = entityManagerFactory.unwrap(SessionFactoryImplementor.class).getSessionFactoryOptions();
        assertThat(options.getJsonFormatMapper()).isInstanceOf(JacksonJsonFormatMapper.class);
    }

    @Test void historicalCachedResponseKeepsExactMinorUnitsDatesAndNullOmission() throws Exception {
        UUID merchant = UUID.randomUUID();
        String body = """
                {"id":"10000000-0000-0000-0000-000000000001",
                 "merchantId":"%s","customerId":"10000000-0000-0000-0000-000000000002",
                 "amountCents":9007199254740993,"currency":"MYR","status":"CREATED",
                 "idempotencyKey":"historical-platform-fixture","metadata":{"reference":"synthetic"},
                 "version":0,"createdAt":"2026-10-18T01:02:03.123456Z",
                 "updatedAt":"2026-10-18T01:02:03.123456Z"}
                """.formatted(merchant);
        jdbc.update("INSERT INTO merchants(id,name,api_key_hash,status) VALUES (?,'Platform fixture',?,'ACTIVE')",
                merchant, merchant.toString());
        try {
            jdbc.update("""
                    INSERT INTO idempotency_records
                    (merchant_id,idempotency_key,request_hash,response_status,response_body,expires_at)
                    VALUES (?,'historical-platform-fixture','historical-hash',201,?,now()+interval '1 hour')
                    """, merchant, body);
            var response = idempotency.getExistingResponse(merchant, "historical-platform-fixture", "historical-hash")
                    .orElseThrow();
            assertThat(response.getAmountCents()).isEqualTo(9007199254740993L);
            assertThat(response.getCreatedAt()).isEqualTo(Instant.parse("2026-10-18T01:02:03.123456Z"));
            assertThat(mapper.readTree(mapper.writeValueAsString(response))).isEqualTo(mapper.readTree(body));
            assertThat(jdbc.queryForObject("SELECT response_body FROM idempotency_records WHERE merchant_id=?",
                    String.class, merchant)).isEqualTo(body);
        } finally {
            jdbc.update("DELETE FROM merchants WHERE id=?", merchant);
        }
    }

    @Test void historicalJsonbPayloadAndHeadersRoundTripWithoutQuotingOrNumericLoss() throws Exception {
        UUID id = UUID.randomUUID();
        String payload = """
                {"eventType":"PaymentInitiatedEvent","version":1,
                 "payload":{"amountCents":9007199254740993,"currency":"MYR","metadata":{"nested":[true,null,"sen"]}}}
                """;
        String headers = """
                {"X-Correlation-Id":"historical-platform-fixture","offset":9007199254740993,"attempt":2}
                """;
        jdbc.update("""
                INSERT INTO payment_events_outbox(id,aggregate_type,aggregate_id,event_type,payload,headers)
                VALUES (?,'PAYMENT',?,'PaymentInitiatedEvent',cast(? as jsonb),cast(? as jsonb))
                """, id, id.toString(), payload, headers);
        try {
            var event = outbox.findById(id).orElseThrow();
            assertThat(mapper.readTree(event.getPayload())).isEqualTo(mapper.readTree(payload));
            assertThat(event.getHeaders().get("offset")).isEqualTo(9007199254740993L);
            event.setRetryCount(1);
            outbox.saveAndFlush(event);
            assertThat(mapper.readTree(jdbc.queryForObject("SELECT payload::text FROM payment_events_outbox WHERE id=?",
                    String.class, id))).isEqualTo(mapper.readTree(payload));
            assertThat(mapper.readTree(jdbc.queryForObject("SELECT headers::text FROM payment_events_outbox WHERE id=?",
                    String.class, id))).isEqualTo(mapper.readTree(headers));
        } finally {
            outbox.deleteById(id);
        }
    }
}
