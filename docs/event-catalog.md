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
| `eventId` | `UUID` | Required, Unique | Global deduplication identifier. Recorded in `consumed_events` to prevent duplicate processing. |
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
| `kaipay.payment.requests` | 3 | 1 (Dev) / 3 (Prod) | `delete` (7 days) | `OutboxEventPublisher` | `PaymentProcessingConsumer` | `aggregateId` (`paymentId`) |
| `kaipay.payment.requests-retry` | 3 | 1 (Dev) / 3 (Prod) | `delete` (3 days) | Spring Kafka Retry Interceptor | `PaymentProcessingConsumer` | `aggregateId` (`paymentId`) |
| `kaipay.payment.requests-dlt` | 3 | 1 (Dev) / 3 (Prod) | `delete` (30 days) | Spring Kafka Retry Interceptor | `@DltHandler` in `PaymentProcessingConsumer` | `aggregateId` (`paymentId`) |

### Partition Key Routing Strategy
To guarantee strict sequential ordering for all events pertaining to a specific payment aggregate, the **`aggregateId` (`paymentId.toString()`)** is used as the Kafka message record key. This ensures that:
- All state changes for payment `P-12345` always land on the **exact same partition**.
- Consumer threads process state transitions (`Initiated` $\to$ `Authorized` $\to$ `Captured`) in strict sequential order.
- Partition scaling does not cause out-of-order race conditions.

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
Published when an authorized payment is captured by the merchant via `POST /v1/payments/{id}/capture`. Triggers financial ledger journal posting and settlement reconciliation.

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
Published when a partial or full refund is successfully executed via `POST /v1/payments/{id}/refunds`. Triggers reversing ledger journal posting and merchant settlement balance adjustments.

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
