# KaiPay Domain Event Catalog & Messaging Specification

This document details all domain events produced and consumed within KaiPay. It defines the universal envelope structure, topic topologies, partition routing key strategies, schema versioning, JSON payload structures, and consumer contracts.

---

## 1. Universal Event Envelope (`EventEnvelope<T>`)

Every domain event published to Kafka is wrapped in a standardized metadata envelope (`EventEnvelope<T>`). This envelope provides distributed tracing identifiers, correlation keys, multi-tenant isolation contexts, schema versioning, and idempotency tokens.

### 1.1. Java Model Reference
[`com.lky.kaipay.common.event.EventEnvelope<T>`](../backend/src/main/java/com/lky/kaipay/common/event/EventEnvelope.java)

```java
public class EventEnvelope<T> {
    private UUID eventId;        // Unique event identifier (Used as Deduplication Key)
    private String eventType;    // Simple class name, e.g. "PaymentInitiatedEvent"
    private String aggregateType;// Aggregate name, e.g. "PAYMENT", "REFUND"
    private String aggregateId;  // Aggregate Root UUID string
    private UUID merchantId;     // Tenant Merchant UUID
    private Instant timestamp;   // UTC creation instant
    private int version;         // Schema version (Default: 1)
    private T payload;           // Typed domain payload
}
```

### 1.2. JSON Wire Schema
```json
{
  "eventId": "a7b8c9d0-1234-5678-9abc-def012345678",
  "eventType": "PaymentInitiatedEvent",
  "aggregateType": "PAYMENT",
  "aggregateId": "4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b",
  "merchantId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "timestamp": "2026-08-24T10:15:30.123456Z",
  "version": 1,
  "payload": {
    "...": "..."
  }
}
```

### 1.3. Envelope Field Definitions

| Field Name | Type | Constraint | Description |
| :--- | :--- | :--- | :--- |
| `eventId` | `UUID` | Required, Unique | Event identity used for authorization deduplication and capture/refund journal correlation. Skipped notifications do not create authorization-group `consumed_events` records. |
| `eventType` | `String` | Required, Non-blank | Canonical event discriminator (`PaymentInitiatedEvent`, `PaymentCapturedEvent`, `PaymentRefundedEvent`). |
| `aggregateType` | `String` | Required, Non-blank | Bounded context entity type (`PAYMENT`, `REFUND`). |
| `aggregateId` | `String` | Required, Non-blank | Primary identifier of the aggregate root (used as Kafka partition message key). |
| `merchantId` | `UUID` | Required | Tenant organization identifier for multi-tenant audit logs and authorization. |
| `timestamp` | `Instant` (ISO-8601) | Required | Monotonic timestamp generated when the event was recorded in the outbox. |
| `version` | `int` | Required ($\ge 1$) | Schema evolution version indicator for forward/backward compatibility. |
| `payload` | `Object` | Required | Strongly-typed event payload object. |

---

## 2. Kafka Topic Topologies & Routing Keys

| Topic Name | Partitions | Replication | Cleanup Policy | Producer(s) | Consumer(s) | Partition Key |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `kaipay.payment.requests` | 3 | 1 (Dev) / 3 (Prod) | `delete` (7 days) | `OutboxEventPublisher` | `PaymentProcessingConsumer` | `aggregateId` (payment ID or refund ID) |
| `kaipay.payment.requests-retry` | 3 | 1 (Dev) / 3 (Prod) | `delete` (3 days) | Spring Kafka Retry Interceptor | `PaymentProcessingConsumer` | Original record key |
| `kaipay.payment.requests-dlt` | 3 | 1 (Dev) / 3 (Prod) | `delete` (30 days) | Spring Kafka Retry Interceptor | `@DltHandler` in `PaymentProcessingConsumer` | Original record key |

### Authorization Consumer Ownership

`PaymentProcessingConsumer`, in `kaipay-payment-processor-group`, authorizes only `PaymentInitiatedEvent`. For compatibility, a message without `eventType` follows the existing initiation path, including support for an unwrapped payload.

Recognized `PaymentCapturedEvent` and `PaymentRefundedEvent` notifications are explicitly acknowledged and skipped before authorization fields are read. This consumer performs no gateway call, payment/refund state change, ledger posting, or consumption-record insertion for them. Accounting already committed in the synchronous service transaction. Their outbox records remain available for publication and operational observation; no webhook, settlement, or reconciliation consumer is implemented.

Unknown event types fail with a controlled non-retryable error and use the existing DLT path. Malformed-message handling and gateway retries remain unchanged. With `MANUAL_IMMEDIATE` acknowledgment and auto-commit disabled, skipped notifications explicitly call `Acknowledgment.acknowledge()`; returning alone does not acknowledge them.

### Partition Key Routing Strategy
The publisher uses the outbox **`aggregateId`** as the Kafka record key. Initiation and capture use the payment ID; refunds use the refund ID. Refund notifications therefore have no guarantee of sharing their parent payment's partition. Capture/refund state transitions and journal posting happen in synchronous API service transactions, not through ordered processing by this authorization consumer.

---

## 3. Domain Event Specifications

### 3.1. `PaymentInitiatedEvent`

#### Purpose
Published immediately after a new payment is created via `POST /v1/payments` and stored in `status = CREATED`. Instructs the downstream asynchronous worker to submit the transaction to the bank acquiring gateway.

#### Java Class
[`com.lky.kaipay.payment.domain.event.PaymentInitiatedEvent`](../backend/src/main/java/com/lky/kaipay/payment/domain/event/PaymentInitiatedEvent.java)

#### Payload Fields

| Field Name | Type | Description | Example |
| :--- | :--- | :--- | :--- |
| `paymentId` | `UUID` | Aggregate root identifier | `4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b` |
| `merchantId` | `UUID` | Tenant merchant identifier | `9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d` |
| `customerId` | `UUID` | Customer making the payment | `3fa85f64-5717-4562-b3fc-2c963f66afa6` |
| `amountCents` | `long` | Transaction amount in integer cents | `10000` ($100.00 USD) |
| `currency` | `String` | ISO 4217 Currency Code | `USD` |
| `idempotencyKey` | `String` | Client-provided idempotency header | `order-20260824-001` |

#### Full Envelope Example
```json
{
  "eventId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "eventType": "PaymentInitiatedEvent",
  "aggregateType": "PAYMENT",
  "aggregateId": "4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b",
  "merchantId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "timestamp": "2026-08-24T10:15:30.123456Z",
  "version": 1,
  "payload": {
    "paymentId": "4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b",
    "merchantId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "customerId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "amountCents": 10000,
    "currency": "USD",
    "idempotencyKey": "order-20260824-001"
  }
}
```

---

### 3.2. `PaymentCapturedEvent`

#### Purpose
Published after an authorized payment is captured via `POST /v1/payments/{id}/capture`. `PaymentService.capturePayment` commits the capture state, ledger journal, and outbox notification together. The event records the completed operation for publication and observation; the authorization consumer acknowledges it without additional accounting or reconciliation.

#### Java Class
[`com.lky.kaipay.payment.domain.event.PaymentCapturedEvent`](../backend/src/main/java/com/lky/kaipay/payment/domain/event/PaymentCapturedEvent.java)

#### Payload Fields

| Field Name | Type | Description | Example |
| :--- | :--- | :--- | :--- |
| `paymentId` | `UUID` | Aggregate root identifier | `4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b` |
| `merchantId` | `UUID` | Tenant merchant identifier | `9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d` |
| `amountCents` | `long` | Captured amount in integer cents | `10000` |
| `currency` | `String` | ISO 4217 Currency Code | `USD` |
| `capturedAt` | `Instant` | UTC timestamp of capture completion | `2026-08-24T10:18:45.000Z` |

#### Full Envelope Example
```json
{
  "eventId": "e2a14925-68a9-467a-9a99-b1d5e30561e1",
  "eventType": "PaymentCapturedEvent",
  "aggregateType": "PAYMENT",
  "aggregateId": "4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b",
  "merchantId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "timestamp": "2026-08-24T10:18:45.100000Z",
  "version": 1,
  "payload": {
    "paymentId": "4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b",
    "merchantId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "amountCents": 10000,
    "currency": "USD",
    "capturedAt": "2026-08-24T10:18:45.000Z"
  }
}
```

---

### 3.3. `PaymentRefundedEvent`

#### Purpose
Published after a partial or full refund succeeds via `POST /v1/payments/{id}/refunds`. `RefundService.createRefund` commits the refund, payment state, reversing journal, and outbox notification together. The event records that completed operation; the authorization consumer acknowledges it without reading `amountCents`, authorizing, or posting additional entries. Merchant balances are projected from the committed database records.

#### Java Class
[`com.lky.kaipay.refund.domain.event.PaymentRefundedEvent`](../backend/src/main/java/com/lky/kaipay/refund/domain/event/PaymentRefundedEvent.java)

#### Payload Fields

| Field Name | Type | Description | Example |
| :--- | :--- | :--- | :--- |
| `refundId` | `UUID` | Unique refund record identifier | `88c21a4f-9e73-4211-88dc-4c8d9e1f2a3b` |
| `paymentId` | `UUID` | Target parent payment identifier | `4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b` |
| `merchantId` | `UUID` | Tenant merchant identifier | `9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d` |
| `refundAmountCents` | `long` | Refund amount in integer cents | `4000` ($40.00 USD) |
| `currency` | `String` | ISO 4217 Currency Code | `USD` |
| `reason` | `String` | Merchant refund rationale | `Customer return item` |
| `refundedAt` | `Instant` | UTC timestamp of refund processing | `2026-08-24T10:25:00.000Z` |

#### Full Envelope Example
```json
{
  "eventId": "c71a3e9b-001a-4f5c-89de-776655443322",
  "eventType": "PaymentRefundedEvent",
  "aggregateType": "REFUND",
  "aggregateId": "88c21a4f-9e73-4211-88dc-4c8d9e1f2a3b",
  "merchantId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "timestamp": "2026-08-24T10:25:00.100000Z",
  "version": 1,
  "payload": {
    "refundId": "88c21a4f-9e73-4211-88dc-4c8d9e1f2a3b",
    "paymentId": "4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b",
    "merchantId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "refundAmountCents": 4000,
    "currency": "USD",
    "reason": "Customer return item",
    "refundedAt": "2026-08-24T10:25:00.000Z"
  }
}
```

---

## 4. Kafka Headers & Metadata Contracts

When messages are transported across Kafka and forwarded to Retry or DLT topics, the following headers are attached by KaiPay and Spring Kafka:

| Header Name | Type | Injected By | Description |
| :--- | :--- | :--- | :--- |
| `merchantId` | `String` | KaiPay Outbox Publisher | Tenant merchant ID string for fast partition filtering. |
| `kafka_original-topic` | `String` | Spring Kafka Retry | The original source topic name before retry routing. |
| `kafka_original-partition` | `int` (4 bytes) | Spring Kafka Retry | Original source partition index. |
| `kafka_original-offset` | `long` (8 bytes) | Spring Kafka Retry | Original source offset. |
| `kafka_exception-fqcn` | `String` | Spring Kafka Retry / DLT | Fully Qualified Class Name of the thrown exception. |
| `kafka_exception-cause-fqcn` | `String` | Spring Kafka Retry / DLT | Root cause FQCN if wrapped inside nested runtime exceptions. |
| `kafka_exception-message` | `String` | Spring Kafka Retry / DLT | Detailed exception message. |

---

## 5. Schema Evolution & Compatibility Rules

KaiPay enforces the following schema evolution rules to ensure zero downtime during rollouts:
1. **Additive Changes Only**: New fields added to event payloads must be optional (nullable) with sensible default values.
2. **Never Rename or Repurpose Fields**: Renaming requires introducing a new field and marking the legacy field as deprecated for 2 minor release cycles.
3. **Version Incrementing**: If a breaking structural change is unavoidable, increment the `version` field in `EventEnvelope<T>` and provide dual deserialization handlers.
