package com.lky.kaipay.common.config;

import com.lky.kaipay.AbstractPostgresIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaInfrastructureIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private NewTopic paymentRequestsTopic;

    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("Kafka topic bean should be configured with 3 partitions and replication factor 1")
    void testKafkaTopicBeanConfiguration() {
        assertThat(paymentRequestsTopic).isNotNull();
        assertThat(paymentRequestsTopic.name()).isEqualTo("kaipay.payment.requests");
        assertThat(paymentRequestsTopic.numPartitions()).isEqualTo(3);
        assertThat(paymentRequestsTopic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("Kafka cluster should be accessible and support produce/consume")
    void testKafkaProduceAndConsume() throws Exception {
        assertThat(kafkaTemplate).isNotNull();

        String testKey = "test-order-123";
        String testPayload = "{\"eventType\":\"PAYMENT_INITIATED\",\"orderId\":\"test-order-123\"}";

        // Send message via KafkaTemplate
        kafkaTemplate.send("kaipay.payment.requests", testKey, testPayload).get(10, TimeUnit.SECONDS);

        // Verify with dedicated KafkaConsumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_CONTAINER.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-verification-group");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(Collections.singletonList("kaipay.payment.requests"));

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records).isNotEmpty();

            boolean found = false;
            for (ConsumerRecord<String, String> record : records) {
                if (testKey.equals(record.key()) && testPayload.equals(record.value())) {
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }
    }
}
