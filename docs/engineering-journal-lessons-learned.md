# KaiPay Engineering Journal & Distributed Systems Lessons Learned

This document captures deep technical reflections, architectural trade-offs, concurrency race conditions, and distributed systems lessons learned during the design and implementation of KaiPay.

---

## 1. The Distributed Dual-Write Problem

### The Trap
A common architectural anti-pattern in distributed payment microservices is writing to the database and publishing to the message broker in immediate succession:

```java
// ANTI-PATTERN: Prone to silent data loss or phantom events!
@Transactional
public void processPayment(PaymentRequest req) {
    Payment payment = paymentRepository.save(new Payment(...)); // (1) DB Write
    kafkaTemplate.send("payment.requests", payment.getId(), payment); // (2) Broker Write
} // (3) DB Commit
```

### Failure Modes:
1. **Case A (Broker Failure)**: If the Kafka cluster times out or is unreachable in step (2), the method throws an exception and rolls back step (1). The payment was not created, but the client received an error.
2. **Case B (Crash Before DB Commit)**: If step (2) succeeds, but the application server crashes or loses database connectivity before step (3) commits, Kafka emits an event for a payment that **never existed in the database** (Ghost Event).
3. **Case C (Publish After Commit)**: If message publishing is placed in a Spring `@TransactionalEventListener(phase = AFTER_COMMIT)` and the JVM crashes immediately after DB commit, the payment exists in the database but **no Kafka event is ever published**, causing a stuck payment.

### The KaiPay Solution: Transactional Outbox Pattern
KaiPay writes the `Payment` aggregate and an outbox event envelope into `payment_events_outbox` in the **exact same ACID database transaction**. An asynchronous poller with `SELECT ... FOR UPDATE SKIP LOCKED` claims and publishes events to Kafka with retry tracking.

---

## 2. Multi-Worker Outbox Polling with `FOR UPDATE SKIP LOCKED`

### The Problem
When scaling out the backend service across multiple instances, multiple outbox poller threads run concurrently. Standard `SELECT ... WHERE status = 'PENDING' FOR UPDATE` causes severe lock contention: all workers attempt to lock the first row, resulting in thread blocking, serialization bottlenecks, and deadlocks.

### The Solution
PostgreSQL's `SKIP LOCKED` clause allows worker threads to bypass rows locked by another transaction:

```sql
SELECT * FROM payment_events_outbox 
WHERE status = 'PENDING' 
ORDER BY created_at ASC 
LIMIT :limit 
FOR UPDATE SKIP LOCKED;
```

#### Key Lessons Learned:
- **Zero Lock Contention**: Each worker thread claims an exclusive, non-overlapping batch of pending events.
- **Ordered Drain**: `ORDER BY created_at ASC` guarantees chronological FIFO event processing per batch.
- **Partial Index Optimization**: `CREATE INDEX idx_outbox_pending ON payment_events_outbox (created_at ASC) WHERE status = 'PENDING';` prevents full table scans as published records grow into millions.

---

## 3. Kafka At-Least-Once Delivery & Consumer Crash Windows

### The Problem
In distributed streaming with Kafka, **exactly-once delivery does not exist across external network boundaries**. Even with Kafka transactional producers (`read_committed`), external side-effects (like calling a third-party bank acquirer REST API) cannot be enrolled in a Kafka or database transaction.

```
Timeline of a Crash Window:
[Step 1: Receive Kafka Message]
          |
[Step 2: Transition DB state to PROCESSING]
          |
[Step 3: Call Bank Acquirer API (Charge $100.00)]  <-- Money moves at the bank!
          |
     💥 JVM / POD CRASHES HERE 💥
          |
[Step 4: Commit DB state to AUTHORIZED] (NEVER REACHED)
[Step 5: Commit Kafka Offset]            (NEVER REACHED)
```

Upon restart, Kafka consumer group rebalances and **redelivers the exact same message**. If the consumer naively executes the method again, the customer will be charged twice ($200.00).

### The KaiPay Solution: Two-Phase Consumer State Machine & Atomic Deduplication
1. **Observable Intermediate State**: Step 2 transitions payment status to `PROCESSING` and commits immediately in its own transaction (Tx 1).
2. **External Gateway Call**: Step 3 executes outside of any database transaction, preventing idle database connection pool exhaustion during gateway latency spikes.
3. **Atomic Final State & Deduplication**: Step 4 records the final status (`AUTHORIZED` / `DECLINED`) and inserts a row into `consumed_events` keyed by `(event_id, consumer_group)` in a single database transaction (Tx 2).
4. **Idempotent Pre-Check**: Upon redelivery after a crash, the pre-check query detects that `consumed_events` contains `eventId` or that the payment is already in `AUTHORIZED` status, skipping the external acquirer call and acknowledging the offset immediately.

---

## 4. Head-of-Line (HoL) Blocking Elimination

### The Problem
In sequential partition processing, if message offset $N$ encounters a temporary network timeout when contacting the bank acquirer, retrying in-place (e.g. `Thread.sleep(5000)`) blocks all subsequent messages on that partition (offsets $N+1$, $N+2$, ...), even if they belong to unrelated merchants whose acquirers are fully operational.

### The Solution: Non-Blocking Retries (`@RetryableTopic`)
KaiPay routes retryable exceptions (`GatewayTimeoutException`, `GatewayUnavailableException`) to a dedicated retry topic (`kaipay.payment.requests-retry`) using Spring Kafka's `@RetryableTopic`.
- The main partition offset is acknowledged immediately.
- Unrelated messages on the main partition continue processing at full wire speed.
- The retry topic consumes with an exponential backoff schedule (1s, 2s) without impacting main traffic.

---

## 5. Financial Ledger Invariants & Real-Time Projections

### The Trap of Mutable Balance Columns
Many naive payment schemas store a mutable column like `merchants.balance_cents = 50000` and execute `UPDATE merchants SET balance_cents = balance_cents + :amount`.

#### Why This Fails in Production:
1. **Race Conditions**: Concurrent transactions overwrite each other's balance updates (lost update anomaly).
2. **Zero Audit Trail**: It is impossible to prove to financial auditors *how* or *why* a balance changed from $500.00 to $450.00 without a complete, immutable journal log.
3. **Floating Point Rounding**: Storing fractional currency in floating point types (`FLOAT`, `DOUBLE`) introduces catastrophic binary rounding errors ($0.1 + 0.2 \neq 0.3$).

### The KaiPay Solution: Double-Entry Ledger & Integer Cents
1. **Integer Cents Only**: All monetary values are strictly represented as `BIGINT` integer cents (e.g. $100.00 USD is stored as `10000L`).
2. **Mathematical Invariant**: Every `Journal` requires $\sum \text{Debits} = \sum \text{Credits}$. The database constraint `amount_cents > 0` prevents negative entry corruptions.
3. **Real-Time Projection**: Balances are calculated by aggregating immutable debit and credit entries using optimized SQL projections.

---

## 6. Summary of Architectural Guarantees

| Invariant / Quality Attribute | Architecture Mechanism | Guarantee Provided |
| :--- | :--- | :--- |
| **No Lost Events** | Transactional Outbox Pattern | Outbox entries commit atomically with payment entities; poller ensures delivery. |
| **Zero Duplicate Charges** | Two-Phase State Guard + `consumed_events` | Redelivered Kafka messages skip bank acquirer calls. |
| **No Partition Stalls** | `@RetryableTopic` Non-Blocking Retries | Transient failures are isolated to retry topics; main topic retains low latency. |
| **Mathematical Ledger Balance** | Double-Entry Journals + Invariant Check | $\sum \text{Debits} - \sum \text{Credits} = 0$ strictly enforced on every financial entry. |
| **Safe Concurrent Refunds** | Pessimistic Locking (`SELECT ... FOR UPDATE`) | Concurrent partial refund requests cannot over-refund a payment. |
| **Multi-Tenant Isolation** | Scoped Unique Indexes & API Key Hashes | Tenants cannot access or corrupt other merchants' customers, payments, or ledgers. |
