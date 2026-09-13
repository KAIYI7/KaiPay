# KaiPay Distributed Systems Learning Roadmap & Concept Blueprint

This document captures the personal technical competencies, architectural mental models, interview readiness questions, and advanced learning milestones extracted from the KaiPay payment engine.

---

## 1. Concepts Mastered Through KaiPay

### 1.1. The Transactional Outbox Pattern with `SKIP LOCKED`

- **Where It Appears**: [`PaymentService.java`](../backend/src/main/java/com/lky/kaipay/payment/service/PaymentService.java#L108-L135), [`OutboxEventPublisher.java`](../backend/src/main/java/com/lky/kaipay/outbox/service/OutboxEventPublisher.java), [`PaymentEventOutboxRepository.java`](../backend/src/main/java/com/lky/kaipay/outbox/repository/PaymentEventOutboxRepository.java#L18-L19).
- **Problem It Solves**: The distributed dual-write problem. Guarantees that database updates and message broker event publishing succeed or fail together without distributed 2PC transactions.
- **How KaiPay Implements It**: Writes `Payment` aggregate and `PaymentEventOutbox` row into PostgreSQL within the same ACID transaction. An asynchronous `@Scheduled` worker polls pending events using `SELECT ... FOR UPDATE SKIP LOCKED` and publishes them to Kafka.
- **Simpler Mental Model**: Writing a physical letter (outbox row) and dropping it into your own local outbox tray before the courier arrives. The letter is permanently documented even if the courier is delayed by traffic.
- **Common Interview Question**: *"How do you reliably publish an event to Kafka after updating a database without losing events during network failures or server crashes?"*
- **What I Should Be Able To Explain**:
  - Why publishing directly to Kafka inside `@Transactional` causes ghost events on rollback.
  - Why `@TransactionalEventListener(phase = AFTER_COMMIT)` loses messages on JVM crash before network transmission.
  - How `FOR UPDATE SKIP LOCKED` allows multiple workers to drain the outbox queue in parallel without thread contention or deadlocks.

---

### 1.2. Two-Phase Consumer Execution (Decoupling DB Transactions from External I/O)

- **Where It Appears**: [`PaymentProcessingConsumer.java`](../backend/src/main/java/com/lky/kaipay/payment/consumer/PaymentProcessingConsumer.java#L80-L89).
- **Problem It Solves**: Database connection pool exhaustion caused by holding transactions open during slow external network calls (e.g. 5-second bank acquirer HTTP latency).
- **How KaiPay Implements It**: Splits consumption into two distinct database transactions:
  1. **Tx 1**: Transitions status to `PROCESSING` and commits immediately.
  2. **Non-Transactional Phase**: Executes `MockBankAcquirerClient.authorize(...)` with zero database locks or connections held.
  3. **Tx 2**: Records final status (`AUTHORIZED` / `DECLINED`) and inserts `consumed_events` deduplication record atomically.
- **Simpler Mental Model**: A restaurant waiter writing down your order and submitting it to the kitchen queue (Tx 1), walking away to attend to other guests while the chef cooks (Non-Tx I/O), and returning only when the dish is ready to serve and bill (Tx 2).
- **Common Interview Question**: *"Why should you never call external third-party HTTP APIs inside a Spring `@Transactional` method?"*
- **What I Should Be Able To Explain**:
  - Connection pool starvation: Holding a HikariCP connection while blocked on external network I/O reduces available throughput for other requests.
  - Transaction timeout risks: Long-running external calls trigger rollback timers or lock escalation.

---

### 1.3. Multi-Layer Idempotency Defense

- **Where It Appears**: [`IdempotencyService.java`](../backend/src/main/java/com/lky/kaipay/payment/service/IdempotencyService.java), [`Payment.java`](../backend/src/main/java/com/lky/kaipay/payment/domain/Payment.java#L39-L41), [`ConsumedEventRepository.java`](../backend/src/main/java/com/lky/kaipay/consumer/repository/ConsumedEventRepository.java).
- **Problem It Solves**: Duplicate payment creations and multiple bank charges caused by network timeouts, client retries, or Kafka message redeliveries.
- **How KaiPay Implements It**:
  1. **API Layer**: SHA-256 request payload hashing and storage in `idempotency_records`.
  2. **Storage Layer**: Unique database constraint `uk_merchant_idempotency` on `(merchant_id, idempotency_key)`.
  3. **Consumer Layer**: Relational table `consumed_events` with composite primary key `(event_id, consumer_group)`.
- **Simpler Mental Model**: A package delivery tracking number. If the postal service receives a package with the same tracking number and destination, they recognize it has already been logged rather than shipping two packages.
- **Common Interview Question**: *"How do you design an API and event consumer to be completely idempotent across network retries?"*
- **What I Should Be Able To Explain**:
  - Why checking "if exists" in memory before saving has a race condition window without database unique constraints.
  - How payload hashing detects key reuse with conflicting arguments.
  - How `consumed_events` ensures at-least-once Kafka consumers produce effectively-once side effects.

---

### 1.4. Double-Entry Accounting & Mathematical Invariants

- **Where It Appears**: [`LedgerService.java`](../backend/src/main/java/com/lky/kaipay/ledger/service/LedgerService.java), [`V5__create_ledger_schema.sql`](../backend/src/main/resources/db/migration/V5__create_ledger_schema.sql).
- **Problem It Solves**: Unauditable balance drift, lost updates, and rounding errors in financial systems.
- **How KaiPay Implements It**:
  - Strictly uses `BIGINT amount_cents` (integer minor units).
  - Every financial event generates an atomic `Journal` containing balanced `LedgerEntry` debits and credits ($\sum \text{Debits} = \sum \text{Credits}$).
  - Account balances are dynamically projected via SQL aggregate queries over immutable ledger rows.
  - Full refund fee retention: Retains $0.30 fixed fee while pro-rating the 2.9% variable fee.
- **Simpler Mental Model**: Every dollar that enters a room must come from one person's pocket (Credit) and go into another person's hand (Debit). Money cannot be created or destroyed out of thin air.
- **Common Interview Question**: *"Why do financial institutions use double-entry bookkeeping instead of an `UPDATE balance` column in SQL?"*
- **What I Should Be Able To Explain**:
  - Complete historical auditability: Every balance change has an explicit source and corresponding offset.
  - Prevention of lost update race conditions in high-concurrency environments.
  - Why floating-point types (`FLOAT`, `DOUBLE`) corrupt monetary values and how integer cents solve it.

---

### 1.5. Non-Blocking Retries & Poison Pill Isolation

- **Where It Appears**: [`PaymentProcessingConsumer.java`](../backend/src/main/java/com/lky/kaipay/payment/consumer/PaymentProcessingConsumer.java#L46-L54), [`PaymentHeadOfLinePartitionIntegrationTest.java`](../backend/src/test/java/com/lky/kaipay/payment/consumer/PaymentHeadOfLinePartitionIntegrationTest.java).
- **Problem It Solves**: Head-of-Line (HoL) blocking on Kafka partitions during transient external partner outages.
- **How KaiPay Implements It**: Uses Spring Kafka `@RetryableTopic` to route transient exceptions (`GatewayTimeoutException`) to dedicated retry topics with exponential backoff (1s, 2s). Fatal poison pills are routed immediately to DLT and persisted in `dead_letter_events`.
- **Simpler Mental Model**: A grocery store checkout line where a customer with a price check issue is moved to a customer service desk (retry topic), allowing the rest of the queue to keep moving.
- **Common Interview Question**: *"What is Head-of-Line blocking in Kafka consumers, and how do you achieve non-blocking retries?"*
- **What I Should Be Able To Explain**:
  - Partition offset ordering constraints in Kafka.
  - Why in-memory retry loops block all subsequent messages on that partition.
  - How multi-topic retry pipelines decouple delayed retries from real-time message flow.

---

## 2. Concepts Requiring Deeper Theoretical Study

| Concept | Where It Appears in KaiPay | Deep Dive Focus Area |
| :--- | :--- | :--- |
| **PostgreSQL MVCC & Isolation Levels** | `PaymentRepository`, `RefundRepository` | How PostgreSQL handles `READ COMMITTED` snapshot creation, lock acquisition order, and tuple visibility during `SELECT ... FOR UPDATE`. |
| **Kafka Group Coordinator & Rebalancing** | `PaymentProcessingConsumer` | Rebalance protocols (Eager vs Incremental Cooperative), heartbeat intervals, `max.poll.interval.ms`, and partition re-assignment during consumer scaling. |
| **Pessimistic vs Optimistic Locking** | `RefundService` vs `Payment.version` | Deadlock graphs, lock escalation, and optimal strategies for high-frequency write contention. |

---

## 3. Professional Engineering Patterns Observed in KaiPay

```
+-----------------------------------------------------------------------------+
| ARCHITECTURAL PATTERN OBSERVED                                              |
+-----------------------------------------------------------------------------+
| 1. Hexagonal / Clean Architecture Module Separation                         |
|    - Isolated domain aggregates (Payment, Journal, Customer)                |
|    - Inbound REST controllers decoupled from outbound repository adapters   |
|                                                                             |
| 2. End-to-End Testcontainer Integration Verification                        |
|    - Zero embedded in-memory database mocks for integration testing         |
|    - Real PostgreSQL 16 & Confluent Kafka 7.6 running in Docker containers   |
|                                                                             |
| 3. Deterministic Amount-Based Test Harness                                  |
|    - MockBankAcquirerClient triggers specific failure modes based on cents  |
|    - $8,888.00 (Transient Timeout) -> $7,777.00 (Exhaustion) -> $6,666.00    |
|      (Poison Pill) -> $9,999.00 (Decline)                                   |
+-----------------------------------------------------------------------------+
```

---

## 4. Advanced Concepts to Learn Next (Deep Dive Blueprint)

---

### Concept 1: Distributed Tracing & OpenTelemetry MDC Correlation

- **Where It Fits**: Cross-cutting observability layer across HTTP Controllers, Outbox Publisher, Kafka Headers, and Kafka Consumers.
- **Problem It Solves**: In asynchronous microservices, a single user request spans multiple threads, database rows, and Kafka messages. Without a correlation ID, debugging a failed payment requires manually searching logs across disjointed timestamps.
- **How to Implement**:
  1. Add a servlet filter to extract or generate `X-Correlation-Id` and insert into SLF4J `MDC.put("traceId", correlationId)`.
  2. Embed `correlationId` into `EventEnvelope.headers` during outbox insertion.
  3. Outbox publisher writes `correlationId` as a Kafka Record Header.
  4. Kafka consumer extracts header and sets `MDC.put("traceId", correlationId)` before processing.
- **Simpler Mental Model**: A luggage barcode tag affixed at airport check-in that is scanned at every conveyor belt, airplane transfer, and baggage claim carousel.
- **Interview Question**: *"How do you trace a single payment request through an asynchronous Kafka-based distributed pipeline?"*

---

### Concept 2: Change Data Capture (CDC) via PostgreSQL WAL & Debezium

- **Where It Fits**: High-throughput alternative to database polling for Transactional Outbox.
- **Problem It Solves**: Outbox polling via SQL (`SELECT ... FOR UPDATE SKIP LOCKED`) creates database read IOPS and query overhead at scales above 10,000 tx/sec.
- **How to Implement**:
  1. Configure PostgreSQL `wal_level = logical`.
  2. Deploy Debezium PostgreSQL Connector in Kafka Connect.
  3. Debezium streams change events directly from PostgreSQL WAL logs to Kafka topics without executing SQL queries.
- **Simpler Mental Model**: Reading a captain's live ship log directly as it is written, rather than knocking on the captain's door every 500ms to ask if anything new happened.
- **Interview Question**: *"What are the tradeoffs between Polling Outbox and CDC-based Outbox using Debezium?"*

---

### Concept 3: Redis Distributed Caching & Fast-Path Idempotency

- **Where It Fits**: Low-latency caching layer for `POST /v1/payments` ingress.
- **Problem It Solves**: Relational database lookups for idempotency checks introduce 2–5ms disk/query overhead.
- **How to Implement**:
  1. Query Redis for key `idempotency:{merchantId}:{key}`.
  2. If hit, return cached response in <1ms.
  3. If miss, acquire a temporary Redis distributed lock (TTL 10s), process payment in PostgreSQL, store response in Redis (TTL 24h), and release lock.
- **Simpler Mental Model**: Keeping a quick-reference cheat sheet on your desk for immediate answers, while the permanent file cabinet remains in the archive room.
- **Interview Question**: *"How do you prevent cache stampedes and handle concurrent requests for the same idempotency key using Redis?"*

---

### Concept 4: Dead Letter Topic (DLT) Redrive & Replay Mechanics

- **Where It Fits**: Administrative maintenance and recovery in `com.lky.kaipay.dlt`.
- **Problem It Solves**: When third-party bank partners suffer prolonged outages (>10 minutes), messages exhaust all retries and enter DLT. Once the partner recovers, operators need an automated way to re-inject messages into the processing pipeline.
- **How to Implement**:
  1. Create REST endpoint `POST /v1/events/dlt/{id}/replay`.
  2. Load `DeadLetterEvent` from database.
  3. Re-publish the original payload to `kaipay.payment.requests`.
  4. Transition original payment from `FAILED` back to `CREATED` or `PROCESSING`.
- **Simpler Mental Model**: An email outbox "retry sending" button for messages that failed while you were offline in airplane mode.
- **Interview Question**: *"How do you safely replay dead-letter messages without causing duplicate payment authorizations?"*

---

### Concept 5: Autonomous Security Audit Logging with `Propagation.REQUIRES_NEW`

- **Where It Fits**: Security, compliance, and merchant authentication audit logging.
- **Problem It Solves**: If an API request encounters a validation error or database constraint failure, the enclosing Spring transaction rolls back, erasing any audit log records written within that transaction.
- **How to Implement**:
  ```java
  @Service
  public class AuditLogService {
      @Transactional(propagation = Propagation.REQUIRES_NEW)
      public void recordSecurityEvent(UUID merchantId, String eventType, String details) {
          auditLogRepository.save(new SecurityAuditLog(merchantId, eventType, details));
      }
  }
  ```
- **Simpler Mental Model**: A security guard at a building entrance logging every person who attempts to enter. Even if a visitor is turned away, their attempt remains recorded in the visitor logbook.
- **Interview Question**: *"How do you ensure audit logs are persisted in the database even when the main business transaction rolls back due to an exception?"*
