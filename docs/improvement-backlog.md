# KaiPay Engineering Improvement Backlog & Architectural Roadmap

This document outlines a realistic, prioritized engineering backlog for the KaiPay platform. Each backlog item contains a technical specification, target files, implementation design, verification strategy, and trade-off analysis.

---

## 1. Prioritization Overview Matrix

| Item ID | Priority Tier | Feature / Architectural Enhancement | Target Area | Business / Technical Value |
| :--- | :--- | :--- | :--- | :--- |
| **`KP-A1`** | **Priority A (High-Value)** | Distributed Correlation ID & MDC Propagation | Ingress, Outbox, Kafka, Consumer | End-to-end distributed traceability across asynchronous threads and network hops. |
| **`KP-A2`** | **Priority A (High-Value)** | DLT Redrive & Replay API (`POST /v1/events/dlt/{id}/replay`) | DLT Admin, Payment FSM, Kafka | Operational recovery and automated re-injection of quarantined payment events. |
| **`KP-A3`** | **Priority A (High-Value)** | Autonomous Security Audit Logger (`REQUIRES_NEW`) | Common Security, Audit Schema | Guaranteed persistence of security, validation, and auth failure logs despite transaction rollbacks. |
| **`KP-B1`** | **Priority B (Learning/Perf)** | Redis Cache Tier for Idempotency Fast-Path | Idempotency Service, Redis Client | Sub-millisecond (<1ms) replay of idempotent API responses; reduces DB read IOPS. |
| **`KP-B2`** | **Priority B (Learning/Perf)** | Asynchronous Outbox Batch Publishing (`CompletableFuture`) | Outbox Publisher, KafkaTemplate | Non-blocking parallel event dispatch; dramatically reduces outbox drain latency. |
| **`KP-B3`** | **Priority B (Learning/Perf)** | Ledger Balance Snapshotting & Settlement Clearing | Ledger Engine, Flyway V6 | Bounds SQL aggregate query execution time over millions of immutable historical entries. |
| **`KP-C1`** | **Priority C (Anti-Pattern)** | Premature Microservices Decomposition | System Architecture | AVOID: Introduces distributed transaction friction and network RPC overhead. |
| **`KP-C2`** | **Priority C (Anti-Pattern)** | Two-Phase Commit (2PC / XA) Transactions | Transaction Management | AVOID: Blocking coordinators and high latency; Outbox pattern is superior. |
| **`KP-C3`** | **Priority C (Anti-Pattern)** | Multi-Region Active-Active DB Replication | Database Infrastructure | AVOID: Asynchronous replication lag causes cross-region financial balance conflicts. |

---

## 2. Priority A: High-Value Production Improvements

---

### Item `KP-A1`: Distributed Correlation ID & MDC Propagation across Kafka Headers

#### 1. Problem Statement & Motivation
Currently, HTTP requests, scheduled outbox polling threads, and asynchronous Kafka consumer executions generate isolated log streams. When an operator investigates a failed payment, there is no shared `traceId` linking the initial `POST /v1/payments` API log to the subsequent outbox publish and Kafka consumer authorization logs.

#### 2. Target Files & Components
- `backend/src/main/java/com/lky/kaipay/common/config/CorrelationIdFilter.java` (New Servlet Filter)
- `backend/src/main/java/com/lky/kaipay/common/event/EventEnvelope.java` (Update headers)
- `backend/src/main/java/com/lky/kaipay/outbox/service/OutboxEventPublisher.java` (Inject Kafka Record Header)
- `backend/src/main/java/com/lky/kaipay/payment/consumer/PaymentProcessingConsumer.java` (Extract Header to MDC)

#### 3. Technical Implementation Design
1. **Servlet Filter**: Intercepts inbound HTTP requests. Extracts `X-Correlation-Id` or generates `UUID.randomUUID()`, setting it into `MDC.put("traceId", correlationId)`.
2. **Outbox Serialization**: Stores `correlationId` inside `PaymentEventOutbox.headers`.
3. **Kafka Header Injection**: `OutboxEventPublisher` copies the correlation ID into the Kafka `ProducerRecord` headers:
   ```java
   ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
   record.headers().add("X-Correlation-Id", correlationId.getBytes(StandardCharsets.UTF_8));
   kafkaTemplate.send(record);
   ```
4. **Consumer MDC Extraction**: `PaymentProcessingConsumer` reads `@Header("X-Correlation-Id")` and sets `MDC.put("traceId", correlationId)` in a `try ... finally` block, ensuring all consumer logs inherit the unified trace context.

#### 4. Verification & Testing
- Write an integration test asserting that log statements from HTTP request, outbox publisher, and Kafka consumer contain the identical `traceId`.

---

### Item `KP-A2`: DLT Redrive & Replay API (`POST /v1/events/dlt/{id}/replay`)

#### 1. Problem Statement & Motivation
When third-party bank partners experience outages, retry attempts are exhausted and events route to the Dead Letter Topic (DLT), where they are persisted in `dead_letter_events`. Currently, `DltAdminController` only allows querying DLT events (`GET /v1/events/dlt`). There is no operational endpoint to re-inject quarantined events back into the active processing queue once the upstream issue is resolved.

#### 2. Target Files & Components
- `backend/src/main/java/com/lky/kaipay/dlt/api/DltAdminController.java`
- `backend/src/main/java/com/lky/kaipay/dlt/service/DltReplayService.java` (New Service)
- `backend/src/main/java/com/lky/kaipay/payment/domain/PaymentStatus.java` (Allow `FAILED -> CREATED` or `FAILED -> PROCESSING` transition for replay)

#### 3. Technical Implementation Design
1. **Endpoint**: `POST /v1/events/dlt/{id}/replay`.
2. **Replay Workflow**:
   ```
   [Admin triggers POST /v1/events/dlt/{id}/replay]
         │
         ▼
   [Load DeadLetterEvent from dead_letter_events table]
         │
         ▼
   [Verify Associated Payment Status == 'FAILED']
         │
         ▼
   [Transition Payment: FAILED -> PROCESSING]
         │
         ▼
   [Re-publish Original Payload to kaipay.payment.requests with 'X-Replay-Count' header]
         │
         ▼
   [Update DeadLetterEvent: mark as REPLAYED]
   ```

#### 4. Verification & Testing
- Create an automated integration test in `DltAdminControllerIntegrationTest`:
  1. Trigger $7,777.00 payment (persistent timeout $\to$ DLT).
  2. Verify DLT record exists and payment is `FAILED`.
  3. Invoke `POST /v1/events/dlt/{id}/replay`.
  4. Verify payment is re-consumed and reaches `AUTHORIZED`.

---

### Item `KP-A3`: Autonomous Security & Compliance Audit Logging Service (`REQUIRES_NEW`)

#### 1. Problem Statement & Motivation
If an API request is rejected due to an authentication error, invalid signature, idempotency hash conflict, or negative refund balance, the database transaction rolls back. Consequently, zero audit rows are written to the database for forensic investigation.

#### 2. Target Files & Components
- `backend/src/main/resources/db/migration/V6__create_security_audit_schema.sql`
- `backend/src/main/java/com/lky/kaipay/common/audit/SecurityAuditLog.java`
- `backend/src/main/java/com/lky/kaipay/common/audit/AuditLogService.java`

#### 3. Technical Implementation Design
1. **Flyway Migration `V6__create_security_audit_schema.sql`**:
   ```sql
   CREATE TABLE security_audit_logs (
       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
       merchant_id UUID,
       action VARCHAR(50) NOT NULL,
       resource_type VARCHAR(50) NOT NULL,
       resource_id VARCHAR(100),
       status VARCHAR(20) NOT NULL, -- SUCCESS, REJECTED, CONFLICT
       ip_address VARCHAR(45),
       details JSONB NOT NULL DEFAULT '{}'::jsonb,
       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
   );
   CREATE INDEX idx_audit_merchant_created ON security_audit_logs(merchant_id, created_at DESC);
   ```
2. **Autonomous Transaction Service**:
   ```java
   @Service
   @RequiredArgsConstructor
   public class AuditLogService {
       private final SecurityAuditLogRepository repository;

       @Transactional(propagation = Propagation.REQUIRES_NEW)
       public void logSecurityEvent(UUID merchantId, String action, String resourceType, String resourceId, String status, Map<String, Object> details) {
           SecurityAuditLog log = SecurityAuditLog.builder()
                   .merchantId(merchantId)
                   .action(action)
                   .resourceType(resourceType)
                   .resourceId(resourceId)
                   .status(status)
                   .details(details)
                   .build();
           repository.save(log);
       }
   }
   ```

#### 4. Verification & Testing
- Trigger an `IdempotencyConflictException` on payment creation and verify that while `payments` has no new row, `security_audit_logs` contains a row with `status = 'CONFLICT'`.

---

## 3. Priority B: Valuable Learning Experiments & Performance Enhancements

---

### Item `KP-B1`: Redis Distributed Cache Integration for Idempotency Fast-Path

#### 1. Problem Statement & Motivation
Currently, every `POST /v1/payments` checks the PostgreSQL `idempotency_records` table via SQL. Under high traffic (e.g. 5,000 req/sec), repeated relational queries add database read load.

#### 2. Technical Implementation Design
- Wire Spring Data Redis (`org.springframework.boot:spring-boot-starter-data-redis`).
- Configure Redis connection on host port `26379`.
- In `IdempotencyService`:
  1. Check Redis key `idemp:{merchantId}:{key}`. If hit, return cached `PaymentResponse` in <1ms.
  2. If miss, proceed with PostgreSQL transaction. Upon commit, populate Redis with TTL = 24 hours.

---

### Item `KP-B2`: Asynchronous Outbox Batch Publishing with `CompletableFuture`

#### 1. Problem Statement & Motivation
In `OutboxEventPublisher.publishPendingEvents`, Kafka publishing executes synchronously inside a loop:
```java
kafkaTemplate.send(topic, key, record.getPayload()).get(2, TimeUnit.SECONDS);
```
Publishing 20 events with an average Kafka round-trip time of 5ms takes $\approx 100\text{ms}$ sequentially.

#### 2. Technical Implementation Design
Dispatch all 20 events concurrently and join via `CompletableFuture.allOf()`:
```java
List<CompletableFuture<Void>> futures = pendingRecords.stream()
    .map(record -> kafkaTemplate.send(topic, record.getAggregateId(), record.getPayload())
        .thenAccept(result -> record.markPublished())
        .exceptionally(ex -> {
            record.recordError(ex.getMessage());
            return null;
        }))
    .toList();

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
outboxRepository.saveAll(pendingRecords);
```
- **Performance Impact**: Reduces 20-record batch publishing latency from ~100ms down to ~10ms.

---

### Item `KP-B3`: Ledger Balance Snapshotting & Settlement Clearing

#### 1. Problem Statement & Motivation
Calculating merchant balances via real-time aggregation (`SELECT SUM(CASE ...)` over `ledger_entries`) is mathematically pure, but over years of transaction history, scanning millions of rows per balance query becomes computationally expensive.

#### 2. Technical Implementation Design
- Introduce an automated nightly scheduled job to calculate closed ledger totals, write a `balance_snapshots` row, and record a `SETTLEMENT` clearing journal.
- Subsequent balance queries aggregate only entries posted after the most recent snapshot timestamp (`WHERE created_at > last_snapshot_timestamp`).

---

## 4. Priority C: Architectural Anti-Patterns to Avoid

---

### Anti-Pattern `KP-C1`: Premature Microservices Decomposition
- **Description**: Splitting KaiPay into 6 separate Git repositories and independent microservice runtimes (e.g. Payment Service, Ledger Service, Outbox Service, Customer Service).
- **Why to Avoid**:
  - Introduces distributed transaction complexity (Saga coordinators, eventual consistency drift).
  - Multiplies deployment overhead and requires API Gateway service meshes.
  - KaiPay's modular monolith already enforces clean package boundaries with zero network latency overhead.

---

### Anti-Pattern `KP-C2`: Two-Phase Commit (2PC / XA) Distributed Transactions
- **Description**: Attempting to synchronize PostgreSQL database commits and Kafka broker publishing using an XA transaction manager.
- **Why to Avoid**:
  - 2PC is a blocking protocol. If the coordinator crashes during the prepare phase, locks remain held indefinitely.
  - High latency penalties ($>50\text{ms}$ per commit).
  - The Transactional Outbox pattern provides superior fault-tolerance with standard local ACID transactions.

---

### Anti-Pattern `KP-C3`: Premature Multi-Region Active-Active Database Clustering
- **Description**: Setting up multi-region active-active PostgreSQL replication across global AWS/GCP regions for payment processing.
- **Why to Avoid**:
  - Cross-region network latency (100–200ms) introduces asynchronous replication lag.
  - Concurrent payments on the same merchant ledger in different regions create write-write conflicts and split-brain balance drift.
  - Single-region primary with warm read-replicas and regional active-passive failover is the proven pattern for financial core ledgers.
