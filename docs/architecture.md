# KaiPay Architecture & System Design Specification

This document provides a technical specification of the KaiPay distributed payment processing and financial ledger engine. It covers bounded contexts, component module architecture, relational schemas (Flyway V1 through V5), aggregate roots, transactional boundaries, and concurrency controls.

---

## 1. High-Level Architectural Overview

KaiPay is architected as a modular distributed application engineered for high-throughput, fault-tolerant financial transactions. It employs the **Hexagonal / Clean Architecture** pattern inside Spring Boot, orchestrating asynchronous processing via **Apache Kafka (KRaft mode)** and persisting authoritative financial state within **PostgreSQL 16**.

```
+---------------------------------------------------------------------------------------------------+
|                                          CLIENT TIER                                              |
|                      React 18 Dashboard / External Merchant API Consumers                        |
+-------------------------------------------------+-------------------------------------------------+
                                                  | HTTPS / REST (JSON + Idempotency-Key)
                                                  v
+---------------------------------------------------------------------------------------------------+
|                                     INBOUND ADAPTERS & API                                        |
|   - MerchantController          - PaymentController           - RefundController                  |
|   - LedgerController            - OutboxAdminController       - DltAdminController                |
|   - GlobalExceptionHandler      - IdempotencyFilter                                               |
+-------------------------------------------------+-------------------------------------------------+
                                                  |
                                                  v
+---------------------------------------------------------------------------------------------------+
|                                      APPLICATION SERVICES                                         |
|   - PaymentService              - RefundService               - LedgerService                     |
|   - MerchantBalanceService      - AccountProvisioningService  - ConsumerDeduplicationService      |
|   - OutboxEventPublisher        - MockBankAcquirerClient                                          |
+------------------------+--------------------------------------------------+-----------------------+
                         |                                                  |
                         v                                                  v
+---------------------------------------------------+  +--------------------------------------------+
|             TRANSACTIONAL PERSISTENCE             |  |            EVENT INFRASTRUCTURE            |
|                   PostgreSQL 16                   |  |          Apache Kafka 3.8 (KRaft)          |
|   - Core Payments & Vault (V1, V2)                |  |   - Topic: kaipay.payment.requests         |
|   - Transactional Outbox (V3)                     |  |   - Topic: kaipay.payment.requests-retry   |
|   - Idempotent Consumer & DLT Audit (V4)          |  |   - Topic: kaipay.payment.requests-dlt     |
|   - Double-Entry Ledger & Refunds (V5)            |  |                                            |
+---------------------------------------------------+  +--------------------------------------------+
```

---

## 2. Bounded Contexts & Module Design

KaiPay is separated into nine bounded contexts:

```
com.lky.kaipay
├── common          # Shared envelopes, API responses, error models, base configurations
├── merchant        # Merchant tenant lifecycle, API keys, webhook URLs, tenant status
├── customer        # Merchant-scoped customer profiles, identities, email constraints
├── payment         # Core payment aggregate, tokenized payment methods, state machine, acquirer client
├── outbox          # Transactional outbox pattern, SKIP LOCKED polling, event publishing
├── consumer        # Kafka message consumer deduplication (consumed_events)
├── dlt             # Dead letter topic consumer handler, audit records, quarantine management
├── ledger          # Double-entry chart of accounts, atomic journals, immutable debit/credit entries
└── refund          # Partial and full refund aggregates, balance validation, reversing entries
```

### Module Responsibilities

1. **Merchant Context (`com.lky.kaipay.merchant`)**:
   - Manages tenant organizations and cryptographic SHA-256 API key authentication.
   - Enforces multi-tenant data isolation across all financial operations.

2. **Customer Context (`com.lky.kaipay.customer`)**:
   - Represents the paying end-user scoped directly under a specific merchant tenant.
   - Maintains a unique constraint on `(merchant_id, email)` to prevent cross-tenant collisions.

3. **Payment Context (`com.lky.kaipay.payment`)**:
   - Manages the lifecycle of payment aggregates, tokenized payment methods (PCI-DSS simulation), and state transitions.
   - Coordinates with external bank acquirers via `MockBankAcquirerClient`.

4. **Outbox Context (`com.lky.kaipay.outbox`)**:
   - Implements the Transactional Outbox pattern to decouple database writes from Kafka message publication.
   - Provides administrative inspection and manual retry endpoints.

5. **Consumer Deduplication Context (`com.lky.kaipay.consumer`)**:
   - Maintains the `consumed_events` table for composite-key deduplication `(event_id, consumer_group)`.

6. **Dead Letter Context (`com.lky.kaipay.dlt`)**:
   - Captures failed messages routed to DLT, recording original topic, partition, offset, exception class, and payload.

7. **Ledger Context (`com.lky.kaipay.ledger`)**:
   - Implements the double-entry accounting engine.
   - Ensures strict zero-sum balancing ($\sum \text{Debits} = \sum \text{Credits}$) for all journals.
   - Provisions system accounts (`1000-CUSTOMER-RECEIVABLE`, `4000-PLATFORM-FEE-REVENUE`) and merchant accounts (`2000-MERCHANT-{ID}-LIABILITY`).

8. **Refund Context (`com.lky.kaipay.refund`)**:
   - Handles partial and full refunds with pessimistic database locking (`PESSIMISTIC_WRITE`) on parent payments.

---

## 3. Database Schema Evolutions (Flyway Migrations V1–V6)

Database migrations are managed sequentially by Flyway.

```
V1__init_payment_schema.sql
  ├── merchants
  ├── customers
  ├── payment_methods
  ├── payments
  └── idempotency_records
V2__seed_dev_merchants.sql
  └── (Default dev merchant seeds)
V3__create_outbox_schema.sql
  └── payment_events_outbox (with partial index)
V4__create_consumed_events_and_dlt_schema.sql
  ├── consumed_events
  └── dead_letter_events
V5__create_ledger_schema.sql
  ├── accounts
  ├── journals
  ├── ledger_entries
  └── refunds
V6__add_outbox_retry_scheduling.sql
  └── Persistent outbox retry/attempt/quarantine timestamps and eligibility index
```

### 3.1. Flyway V1: Core Relational Schema

```sql
-- Merchants
CREATE TABLE merchants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    api_key_hash VARCHAR(64) NOT NULL,
    webhook_url VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_merchants_api_key_hash UNIQUE (api_key_hash)
);

-- Customers (Scoped to Merchant)
CREATE TABLE customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    email VARCHAR(150) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_customers_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE RESTRICT,
    CONSTRAINT uk_merchant_customer_email UNIQUE (merchant_id, email)
);
CREATE INDEX idx_customers_merchant ON customers(merchant_id);

-- Payment Methods (Tokenized Vault)
CREATE TABLE payment_methods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL,
    type VARCHAR(30) NOT NULL,
    token VARCHAR(100) NOT NULL,
    masked_number VARCHAR(20) NOT NULL,
    expiry_month INT NOT NULL CHECK (expiry_month BETWEEN 1 AND 12),
    expiry_year INT NOT NULL CHECK (expiry_year >= 2024),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payment_methods_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT,
    CONSTRAINT uk_payment_methods_token UNIQUE (token)
);
CREATE INDEX idx_payment_methods_customer ON payment_methods(customer_id);

-- Payments Aggregate Root
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    payment_method_id UUID,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    gateway_reference VARCHAR(100),
    failure_code VARCHAR(50),
    failure_message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payments_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payments_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payments_method FOREIGN KEY (payment_method_id) REFERENCES payment_methods(id) ON DELETE RESTRICT,
    CONSTRAINT uk_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
);
CREATE INDEX idx_payments_merchant_created ON payments(merchant_id, created_at DESC);
CREATE INDEX idx_payments_status ON payments(status);

-- API Idempotency Records
CREATE TABLE idempotency_records (
    merchant_id UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (merchant_id, idempotency_key),
    CONSTRAINT fk_idempotency_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE CASCADE
);
CREATE INDEX idx_idempotency_expires_at ON idempotency_records(expires_at);
```

### 3.2. Flyway V3: Transactional Outbox Schema

```sql
CREATE TABLE payment_events_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ
);

-- Partial index optimizing poller performance by only scanning PENDING records
CREATE INDEX idx_outbox_pending ON payment_events_outbox (created_at ASC) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON payment_events_outbox (aggregate_type, aggregate_id);
```

### 3.3. Flyway V4: Idempotent Consumer & DLT Schema

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
CREATE INDEX idx_consumed_events_payment ON consumed_events (payment_id);

CREATE TABLE dead_letter_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_topic VARCHAR(100) NOT NULL,
    original_partition INT NOT NULL,
    original_offset BIGINT NOT NULL,
    event_id UUID,
    payment_id UUID,
    exception_class VARCHAR(255) NOT NULL,
    failure_message TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_dlt_payment ON dead_letter_events (payment_id);
CREATE INDEX idx_dlt_created ON dead_letter_events (created_at DESC);
```

### 3.4. Flyway V5: Double-Entry Ledger & Refunds Schema

```sql
-- Accounts (Chart of Accounts)
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID REFERENCES merchants(id), -- NULL for platform/system accounts
    account_number VARCHAR(64) NOT NULL UNIQUE,
    account_name VARCHAR(100) NOT NULL,
    account_type VARCHAR(30) NOT NULL, -- ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_accounts_merchant ON accounts (merchant_id);

-- Journals (Atomic Financial Transaction Header)
CREATE TABLE journals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_number VARCHAR(64) NOT NULL UNIQUE,
    source_type VARCHAR(50) NOT NULL, -- PAYMENT_CAPTURE, PAYMENT_REFUND, SETTLEMENT
    source_id VARCHAR(100) NOT NULL,
    event_id UUID,
    merchant_id UUID REFERENCES merchants(id),
    description TEXT NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_journals_source_event UNIQUE (source_type, source_id, event_id)
);
CREATE INDEX idx_journals_merchant ON journals (merchant_id);
CREATE INDEX idx_journals_source ON journals (source_type, source_id);

-- Ledger Entries (Immutable Debits and Credits)
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_id UUID NOT NULL REFERENCES journals(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    entry_type VARCHAR(10) NOT NULL, -- DEBIT, CREDIT
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ledger_entries_journal ON ledger_entries (journal_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id);

-- Refunds
CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES payments(id),
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(30) NOT NULL DEFAULT 'COMPLETED',
    reason VARCHAR(255),
    idempotency_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_refunds_idempotency UNIQUE (merchant_id, idempotency_key)
);
CREATE INDEX idx_refunds_payment ON refunds (payment_id);
CREATE INDEX idx_refunds_merchant ON refunds (merchant_id);
```

---

## 4. Payment State Machine Lifecycle

Payments transition through a deterministic finite-state automaton:

```
               +------------------------------------+
               |              CREATED               |
               +-----------------+------------------+
                                 |
                                 | (Asynchronous Consumer Pickup)
                                 v
               +------------------------------------+
               |             PROCESSING             |
               +--------+------------------+--------+
                        |                  |
   (Acquirer Approved)  |                  | (Acquirer Declined / Card Error)
                        v                  v
+-----------------------------+      +-----------------------------+
|         AUTHORIZED          |      |          DECLINED           |
+--------------+--------------+      +-----------------------------+
               |
               | (POST /payments/{id}/capture)
               v
+-----------------------------+
|          CAPTURED           | <----------------------+
+--------------+--------------+                        |
               |                                       | (Partial Refund)
               +-------------------+                   |
               | (Partial Refund)  | (Full Refund)     |
               v                   v                   |
+-----------------------------+  +-----------------------------+
|      PARTIALLY_REFUNDED     |  |          REFUNDED           |
+--------------+--------------+  +-----------------------------+
               |                               ^
               +-------------------------------+
                      (Subsequent Refund to 100%)

   * Note: In case of fatal poison pills or DLT exhaustion, 
     CREATED / PROCESSING transitions to:
               +-----------------------------+
               |           FAILED            |
               +-----------------------------+
```

### State Machine Invariants

| From State | Allowed Target States | Trigger Action | Transaction Scope |
| :--- | :--- | :--- | :--- |
| `CREATED` | `PROCESSING`, `FAILED` | Consumer pickup or poison pill handling | Database Transaction |
| `PROCESSING` | `AUTHORIZED`, `DECLINED`, `FAILED` | Acquirer result or DLT exhaustion | Database Transaction |
| `AUTHORIZED` | `CAPTURED`, `FAILED` | Merchant Capture API call | Database Transaction |
| `CAPTURED` | `PARTIALLY_REFUNDED`, `REFUNDED` | Merchant Refund API call | Database Transaction (with Pessimistic Lock) |
| `PARTIALLY_REFUNDED`| `PARTIALLY_REFUNDED`, `REFUNDED` | Subsequent Partial / Full Refund | Database Transaction (with Pessimistic Lock) |
| `DECLINED` | *(Terminal)* | None | N/A |
| `REFUNDED` | *(Terminal)* | None | N/A |
| `FAILED` | *(Terminal)* | None | N/A |

---

## 5. Transaction Boundaries & Concurrency Model

### Boundary 1: Payment Creation (`POST /v1/payments`)
- **Transaction Scope**: Single `@Transactional` method in `PaymentService.createPayment`.
- **Operations**:
  1. Check `idempotency_records` cache.
  2. Validate merchant, customer, and payment method.
  3. Insert into `payments` (`status = CREATED`).
  4. Insert into `payment_events_outbox` (`event_type = PaymentInitiatedEvent`, `status = PENDING`).
  5. Insert into `idempotency_records`.
- **Guarantee**: If database insertion fails or unique constraint triggers, entire transaction rolls back cleanly. Outbox row and payment row commit together.

### Boundary 2: Outbox Publishing Worker (`OutboxEventPublisher`)
- **Transaction Scope**: `@Scheduled` polling method wrapped in `@Transactional`.
- **Concurrency Mechanism**:
  ```sql
  SELECT * FROM payment_events_outbox 
  WHERE status = 'PENDING'
    AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
  ORDER BY COALESCE(next_attempt_at, created_at), created_at, id
  LIMIT :limit 
  FOR UPDATE SKIP LOCKED;
  ```
- **Operations**:
  1. Claims batch of pending events without blocking competing worker threads.
  2. Publishes to Kafka broker (`kafkaTemplate.send(...)` with a configurable future wait, default 4 seconds).
  3. Updates row status to `PUBLISHED` with `published_at = NOW()`.
  4. On send failure, retains the row, error class/message, attempt timestamp and cumulative failure count. It continues with the other eligible rows.

Flyway V6 adds nullable `next_attempt_at`, `last_attempt_at`, and `quarantined_at`; existing V5 rows remain eligible without payload/status changes. The pending index follows the eligibility ordering. New events use their creation time; retries move to a future eligibility time, so a repeatedly failing older row cannot permanently occupy the front of the queue even with batch size one. Row locks are held through send completion and the batch's database commit. Competing workers skip locked claims; this does not prevent duplicates after rollback or a crash.

**Retry policy:** Send timeouts (including ambiguous outcomes), broker outages, authorization/configuration failures, serialization failures, and unclassified errors remain `PENDING` with exponential backoff: 1s, 2s, 4s, ... capped at 60s by default. There is no finite retry budget for these errors. Delays and the positive send timeout are configured under `kaipay.outbox`; maximum retry delay must be at least the initial delay and at most 24h. Each failure's next deadline is persisted after the failed send. Eligibility uses the worker's UTC clock; worker clocks must be synchronized. No jitter, global outage circuit breaker, or distributed scheduler is introduced.

Only `RecordTooLargeException` is classified as a record-specific permanent rejection. It immediately changes the retained row to `QUARANTINED`, records the error/time, and excludes it from automatic polling. Prior transient failures do not consume a quarantine budget. Quarantine requires operator remediation; it does not mean successful delivery or silent deletion. `GET /v1/events/outbox?status=QUARANTINED` exposes retained payloads/errors and the added timing fields; the Outbox Stream displays the new status explicitly. Other non-retriable Kafka errors may describe a repairable global configuration problem and deliberately remain scheduled for retry. Malformed event JSON is still the consumer's responsibility.

**Recovery procedure:** Inspect the quarantined event by ID through the existing outbox API or database, save the original record/error in the incident record, and correct the demonstrated cause (for example, restore supported producer/broker size limits). Changing a financial event's meaning is not an automatic recovery strategy. Then use an operator database session:

```sql
BEGIN;
SELECT id, status, payload, retry_count, last_error, quarantined_at
FROM payment_events_outbox WHERE id = '<reviewed-event-uuid>' FOR UPDATE;
UPDATE payment_events_outbox
SET status = 'PENDING', next_attempt_at = NOW()
WHERE id = '<reviewed-event-uuid>'
  AND status = 'QUARANTINED' AND published_at IS NULL
RETURNING id, status, next_attempt_at;
COMMIT;
```

Confirm exactly one row was returned, then confirm it reaches `PUBLISHED` or investigate its new error. Requeue retains the original ID, payload, failure count, last error and last quarantine timestamp; a successful publish clears the active retry deadline. There is no new recovery API or DLT replay mechanism. Deploy the migration and updated publisher/UI together, with old publisher instances stopped: old code ignores deadlines and cannot deserialize the new status.

**Delivery limits:** Kafka acknowledgment precedes database commit. If commit fails or the process crashes afterward, the row remains pending and may be sent again. A future timeout does not cancel the send and cannot establish non-delivery; late successes are not applied asynchronously to the JPA entity. Database persistence errors escape the send-failure classifier and roll back the batch. This remains an at-least-once relay with operator recovery for quarantined records, not exactly-once delivery. Backoff, quarantine and concurrent workers can change publication order, including within an aggregate. The synchronous Kafka `send` itself may wait for metadata up to the existing producer `max.block.ms`; the future wait is not a total invocation deadline. On thread interruption the worker retains retry state, restores the flag, and stops the batch.

### Boundary 3: Asynchronous Consumer Two-Phase Processing (`PaymentProcessingConsumer`)

This boundary applies to `PaymentInitiatedEvent` authorization. The existing topic/group also receives capture/refund notifications, which are explicitly acknowledged and skipped before reading authorization-specific fields. They create no authorization-group consumption records and trigger no state changes or ledger writes. Unknown event types use the existing non-retryable DLT path; missing `eventType` retains the legacy initiation fallback.

To avoid holding a database connection open during slow external bank HTTP calls, processing is split into two independent transactions:
- **Pre-check**: Queries `consumed_events` (read-only).
- **Transaction A (Tx 1)**: `PaymentService.transitionToProcessing(paymentId)` commits `status = PROCESSING`.
- **Non-Transactional Phase**: `MockBankAcquirerClient.authorize(...)` executes without holding any database locks.
- **Transaction B (Tx 2)**: `PaymentService.completeAuthorizationWithDeduplication(...)` updates payment status to `AUTHORIZED` or `DECLINED` and inserts the `consumed_events` record in a single atomic commit.

### Boundary 4: Payment Capture & 3-Way Journal Posting (`POST /v1/payments/{id}/capture`)
- **Transaction Scope**: `@Transactional` method in `PaymentService.capturePayment`.
- **Operations**:
  1. Verifies payment status is `AUTHORIZED`.
  2. Transitions status to `CAPTURED`.
  3. Writes a completed-operation `PaymentCapturedEvent` notification to `payment_events_outbox`.
  4. Calculates platform processing fee ($2.9\% + \$0.30$).
  5. Inserts `journals` row and 3 balanced `ledger_entries` rows (Debit Receivable, Credit Payable, Credit Revenue).

### Boundary 5: Refund Execution (`POST /v1/payments/{id}/refunds`)
- **Transaction Scope**: `@Transactional` method in `RefundService.createRefund`.
- **Pessimistic Locking**:
  ```sql
  SELECT * FROM payments WHERE id = :paymentId AND merchant_id = :merchantId FOR UPDATE;
  ```
- **Operations**:
  1. Locks payment aggregate to prevent concurrent over-refund race conditions.
  2. Aggregates existing refunds (`sumRefundedAmountByPaymentId`).
  3. Validates `requestedAmount <= (totalAmount - refundedAmount)`.
  4. Inserts `refunds` record.
  5. Updates payment status to `PARTIALLY_REFUNDED` or `REFUNDED`.
  6. Writes a completed-operation `PaymentRefundedEvent` notification to `payment_events_outbox`.
  7. Calls `ledgerService.recordPaymentRefund` to post reversing ledger journal.

Capture/refund accounting remains synchronous and atomic with these service transactions. Outbox publication provides notification and operational visibility; no downstream accounting, webhook delivery, settlement, or reconciliation handler is implemented.

---

## 6. Dedicated Port Allocations & Multi-Service Isolation

KaiPay enforces strict non-overlapping host port allocations across the infrastructure stack:

| Service | Container / Component | Host Port | Container Port | Protocol / URI |
| :--- | :--- | :--- | :--- | :--- |
| **KaiPay Backend API** | Spring Boot 3.4.3 | **`28080`** | `28080` | `http://localhost:28080` |
| **KaiPay Frontend UI** | Vite / React 18.3.1 | **`28081`** | `28081` | `http://localhost:28081` |
| **KaiPay PostgreSQL** | Docker `postgres:16-alpine` | **`25432`** | `5432` | `jdbc:postgresql://localhost:25432/kaipay` |
| **KaiPay Apache Kafka** | Docker `apache/kafka:3.8.0` | **`29092`** | `9092` | `localhost:29092` |
| **KaiPay Kafka-UI** | Docker `provectuslabs/kafka-ui` | **`28048`** | `8080` | `http://localhost:28048` |
| **KaiPay Redis (Reserved)** | Docker `redis:7-alpine` | **`26379`** | `6379` | `localhost:26379` |

