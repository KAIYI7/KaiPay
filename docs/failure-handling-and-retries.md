# KaiPay Failure Handling, Non-Blocking Retries & DLT Architecture

In a distributed financial system, transient network glitches, bank partner outages, poison pills, and concurrent race conditions are inevitable. KaiPay is architected to guarantee that failures never result in silent message loss, duplicate customer charges, or head-of-line blocking on Kafka partitions.

---

## 1. Failure Classification Taxonomy

KaiPay classifies all potential execution failures into five distinct categories:

| Category | Typical Scenarios & Exceptions | Retry Policy | Terminal Action | Invariant / Target State |
| :--- | :--- | :--- | :--- | :--- |
| **1. Transient Network / Gateway Latency** | `GatewayTimeoutException`, `GatewayUnavailableException`, HTTP 503, Socket Timeout | **Retryable** (Exponential Backoff: 1s, 2s) | Retry up to 3 attempts via dedicated retry topic | Payment remains in `PROCESSING` during retry attempts |
| **2. Non-Retryable Gateway / Fatal System Errors** | `NonRetryableGatewayException`, Invalid API credentials, 4xx Bad Request from Acquirer | **Non-Retryable** (Immediate Escalate) | Direct routing to DLT | Payment transitioned to `FAILED`, DLT row created |
| **3. Business Gateway Declines** | Insufficient funds, expired card, fraud rule triggers, do not honor | **Non-Retryable** (Normal Domain Outcome) | Commit final state, acknowledge message | Payment transitioned to `DECLINED`, event logged in `consumed_events` |
| **4. Unresolvable Poison Pills** | Malformed JSON, corrupted envelope, missing required fields (`paymentId`) | **Non-Retryable** (Immediate Quarantine) | Route directly to DLT, acknowledge offset | Raw payload saved in `dead_letter_events` |
| **5. Transient Database Locking / Optimistic Lock** | `OptimisticLockingFailureException`, DB Connection Glitch | **Retryable** | Kafka retry mechanism redelivers | Re-evaluates state and succeeds on next attempt |

### 1.1. Deterministic Failure Simulation Triggers (`MockBankAcquirerClient`)

To guarantee deterministic, automated end-to-end integration testing and interactive UI demonstration, `MockBankAcquirerClient` implements specific amount triggers:

| Trigger Amount | Amount in Cents | Simulated Behavior | Kafka / Consumer Lifecycle | Terminal Payment Status |
| :--- | :--- | :--- | :--- | :--- |
| **`$8,888.00`** | `888800L` | **Transient Gateway Timeout**: Throws `GatewayTimeoutException` on attempts 1 & 2; recovers and returns `APPROVED` on attempt 3. | Routed to `kaipay.payment.requests-retry` with exponential backoff (1s, 2s). Succeeds on retry attempt 3. | `AUTHORIZED` |
| **`$7,777.00`** | `777700L` | **Persistent Gateway Timeout / Retry Exhaustion**: Throws `GatewayTimeoutException` continuously across all attempts. | Fails on main topic, fails on retry attempt 1, fails on retry attempt 2 $\to$ routed to `kaipay.payment.requests-dlt`. | `FAILED` (`DLT_ROUTED`) |
| **`$6,666.00`** | `666600L` | **Fatal Non-Retryable Error**: Throws `NonRetryableGatewayException`. | Not included in `@RetryableTopic` include list $\to$ bypassed directly to `kaipay.payment.requests-dlt` without retries. | `FAILED` (`DLT_ROUTED`) |
| **`$9,999.00`** | `999900L` | **Deterministic Business Decline**: Returns declined `AcquirerAuthorizationResult` with code `INSUFFICIENT_FUNDS`. | Evaluated as a valid domain business outcome. No Kafka exception thrown; offset acknowledged immediately. | `DECLINED` |

---

## 2. Non-Blocking Retries vs. Head-of-Line Blocking

### The Problem with Blocking Retries in Message Queues
In traditional Kafka consumer configurations, retrying a transient failure using in-memory `Thread.sleep` or synchronous blocking loops causes **Head-of-Line (HoL) Blocking**:
- Kafka guarantees partition offset ordering. If message at offset `100` fails and retries for 30 seconds, offsets `101` through `1000` on that same partition cannot be processed.
- If 10 merchants share a partition and 1 merchant encounters an acquirer timeout, all 9 other merchants experience high latency.

### The KaiPay Solution: Non-Blocking Multi-Topic Retries
KaiPay uses Spring Kafka's non-blocking `@RetryableTopic` pattern:

```
[ Topic: kaipay.payment.requests ] (Main Topic)
  |
  +-- Message 1 (Success) -----> Authorized (Ack offset 0)
  +-- Message 2 (Timeout) ------> Forward to [ kaipay.payment.requests-retry ] (Ack offset 1)
  +-- Message 3 (Success) -----> Authorized (Ack offset 2)  <-- ZERO DELAY! No HoL blocking!
  |
[ Topic: kaipay.payment.requests-retry ] (Retry Topic)
  |
  +-- Retry 1 (Waits 1000ms backoff) --> Failed again
  +-- Retry 2 (Waits 2000ms backoff) --> Failed again (Exhausted)
  |
  v
[ Topic: kaipay.payment.requests-dlt ] (Dead Letter Topic)
  |
  v
[@DltHandler in PaymentProcessingConsumer]
  ├── Mark Payment status = 'FAILED' (failure_code='DLT_ROUTED')
  ├── Persist audit record in dead_letter_events table
  └── Acknowledge DLT offset
```

---

## 3. Implementation Specification

### 3.1. Spring Kafka Consumer Configuration
[`com.lky.kaipay.payment.consumer.PaymentProcessingConsumer`](../backend/src/main/java/com/lky/kaipay/payment/consumer/PaymentProcessingConsumer.java)

```java
@RetryableTopic(
        attempts = "3",
        backoff = @Backoff(delay = 1000, multiplier = 2.0),
        dltTopicSuffix = "-dlt",
        retryTopicSuffix = "-retry",
        sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
        autoCreateTopics = "true",
        include = {GatewayTimeoutException.class, GatewayUnavailableException.class}
)
@KafkaListener(
        topics = "${kaipay.kafka.topics.payment-requests:kaipay.payment.requests}",
        groupId = "${spring.kafka.consumer.group-id:kaipay-payment-processor-group}"
)
public void processPaymentRequest(ConsumerRecord<String, String> record, Acknowledgment ack) {
    // 1. Fast pre-check deduplication
    if (consumerDeduplicationService.isEventConsumed(eventId, consumerGroup)) {
        if (ack != null) ack.acknowledge();
        return;
    }

    // 2. Tx 1: Transition status CREATED -> PROCESSING
    paymentService.transitionToProcessing(paymentId);

    // 3. Non-Transactional Gateway Call (Zero DB locks held)
    AcquirerAuthorizationResult authResult = mockBankAcquirerClient.authorize(paymentId, amountCents, currency);

    // 4. Tx 2: Atomic state update (AUTHORIZED / DECLINED) + ConsumedEvent insertion
    paymentService.completeAuthorizationWithDeduplication(paymentId, authResult, eventId, consumerGroup, eventType);

    if (ack != null) ack.acknowledge();
}
```

---

## 4. Consumer Deduplication & Effectively-Once Processing

Kafka guarantees **at-least-once** delivery. Under network disconnections, rebalances, or process crashes between step 3 (acquirer execution) and Kafka offset commit, Kafka will redeliver the message.

To prevent duplicate bank charges upon redelivery, KaiPay implements a **two-phase state guard with atomic deduplication recording**:

```
+-----------------------------------------------------------------------------------------------+
| PaymentProcessingConsumer.processPaymentRequest(record)                                       |
+-----------------------------------------------------------------------------------------------+
                                                |
                                                v
                    +-------------------------------------------------------+
                    |  Pre-Check: consumed_events.exists(eventId, group)    |
                    +---------------------------+---------------------------+
                                                |
                        +-----------------------+-----------------------+
                        | Yes                                           | No
                        v                                               v
        +-------------------------------+               +-------------------------------+
        | Fast Return: Acknowledge & Skip |             | Tx 1: status = PROCESSING     |
        +-------------------------------+               +---------------+---------------+
                                                                        |
                                                                        v
                                                        +-------------------------------+
                                                        | Non-Tx External Gateway Call  |
                                                        | (Acquirer authorize API)      |
                                                        +---------------+---------------+
                                                                        |
                                                                        v
                                                        +-------------------------------+
                                                        | Tx 2 (Atomic Local DB Tx):    |
                                                        |  - status = AUTHORIZED/DECLINED|
                                                        |  - INSERT consumed_events     |
                                                        +---------------+---------------+
                                                                        |
                                                                        v
                                                        +-------------------------------+
                                                        | Kafka Acknowledgment (Ack)    |
                                                        +-------------------------------+
```

### Relational Schema of `consumed_events`
```sql
CREATE TABLE consumed_events (
    event_id UUID NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    payment_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_consumed_events PRIMARY KEY (event_id, consumer_group)
);
```

### Crash Recovery Invariants:
1. **Crash before Tx 1**: Redelivered message sees payment in `CREATED`. Tx 1 starts cleanly. No bank call was made.
2. **Crash during Gateway Call**: Redelivered message sees payment in `PROCESSING`. Gateway call is retried or resolved with acquirer reference.
3. **Crash after Tx 2 before Kafka Ack**: Redelivered message hits **Fast Pre-Check** (`consumed_events` entry exists). Consumer logs dedup hit, acknowledges offset immediately, and exits without re-calling the bank.

---

## 5. Dead Letter Topic (DLT) & Quarantine Auditing

When retries are exhausted (after 3 attempts with exponential backoff) or a non-retryable exception / poison pill occurs, Spring Kafka invokes the `@DltHandler`:

```java
@DltHandler
public void handleDltMessage(
        ConsumerRecord<String, String> record,
        @Header(value = KafkaHeaders.EXCEPTION_FQCN, required = false) String exceptionFqcn,
        @Header(value = KafkaHeaders.EXCEPTION_CAUSE_FQCN, required = false) String exceptionCauseFqcn,
        @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String exceptionMessage,
        Acknowledgment ack
) {
    // 1. Mark Payment aggregate as FAILED
    if (paymentId != null) {
        paymentService.markPaymentFailed(paymentId, "DLT_ROUTED", exceptionMessage);
    }

    // 2. Persist full forensic audit record in dead_letter_events
    DeadLetterEvent dltEvent = DeadLetterEvent.builder()
            .originalTopic(record.topic())
            .originalPartition(record.partition())
            .originalOffset(record.offset())
            .eventId(eventId)
            .paymentId(paymentId)
            .exceptionClass(resolvedExceptionClass)
            .failureMessage(exceptionMessage)
            .payload(jsonPayload)
            .headers(headersMap)
            .build();
    deadLetterEventRepository.save(dltEvent);

    if (ack != null) ack.acknowledge();
}
```

### DLT Administrative API
KaiPay provides REST APIs for operational platform engineering and support:
- `GET /v1/events/dlt` — Pageable list of quarantined messages with exception traces and payment search filter (`DltAdminController`).
- `GET /v1/events/dlt?paymentId={id}` — Quarantined event lookup by payment aggregate ID.
- *(Roadmap / Planned)*: `POST /v1/events/dlt/{id}/replay` — Reprocess or replay quarantined events after upstream fix.
