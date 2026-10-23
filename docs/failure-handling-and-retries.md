# Failure handling, retries and authorization recovery

KaiPay uses durable local simulator evidence, retry topics and dead-letter diagnostics to handle bounded failure scenarios. It does not connect to a real acquirer or promise lossless delivery, zero latency or exactly-once external effects.

## Durable authorization boundary

The [consumer](../backend/src/main/java/com/lky/kaipay/payment/consumer/PaymentProcessingConsumer.java) handles initiation in three committed steps:

1. [Preparation](../backend/src/main/java/com/lky/kaipay/payment/service/AuthorizationRecoveryService.java) locks and validates the payment, creates its durable authorization intent and transitions CREATED to PROCESSING in one transaction.
2. The [simulator transaction](../backend/src/main/java/com/lky/kaipay/payment/service/acquirer/SimulatedAuthorizationTransactionService.java) locks the retained operation, evaluates or reuses its definitive result and commits independently of payment finalization. The caller has no surrounding payment transaction; the simulator itself does use a short database transaction.
3. Finalization validates the retained result and commits AUTHORIZED, DECLINED or definitive FAILED, the consumption marker and any enrolled webhook intent together. Only then does normal processing acknowledge Kafka.

The simulator and KaiPay share PostgreSQL. This is a durable simulation with a shared database failure boundary, not an independent provider or bank guarantee. The operation is keyed by payment ID and bound to merchant, amount, currency, namespace and contract version. Repeated deliveries may reevaluate an unresolved operation, but an existing definitive simulator result is reused.

## Deterministic synthetic scenarios

The [simulator model](../backend/src/main/java/com/lky/kaipay/payment/service/acquirer/SimulatedAuthorizationOperation.java) defines these MYR inputs. `amountCents` is the API's integer-sen field.

| Input | Simulator behavior | Payment result |
| --- | --- | --- |
| RM8,888.00 (`888800`) | First two durable evaluations remain unresolved; third approves. | AUTHORIZED after successful retry/finalization. |
| RM7,777.00 (`777700`) | Every evaluation remains unresolved and the caller reports timeout. | PROCESSING after retry exhaustion; DLT is diagnostic. |
| RM6,666.00 (`666600`) | Commits REJECTED_NO_ACCEPTANCE. | FAILED with `SIMULATED_NO_ACCEPTANCE`, then diagnostic DLT routing. |
| RM9,999.00 or more (`>=999900`) | Commits a business decline. | DECLINED; normal acknowledgment. |
| Other positive supported amounts | Commits approval. | AUTHORIZED after finalization. |

These triggers describe simulator evaluation, not transport timing guarantees. Use synthetic customers assigned to the authenticated merchant; no real funds move.

## Retry topology and ordering

`@RetryableTopic` allows three total attempts with 1-second initial backoff and multiplier 2. It includes `GatewayTimeoutException`, `GatewayUnavailableException` and `AuthorizationPersistenceException`. Selected database/connection failures are wrapped as authorization persistence failures; not every database or programming error is retryable. Unsupported events and malformed initiation data take the fatal/DLT path.

```text
Initiation topic -> preparation -> simulator -> finalization -> acknowledge
       |
       +-> retry topic(s), with backoff -> retry processing
                                      |
                                      +-> exhausted/fatal -> DLT diagnostics
```

Retry topics let later main-topic records progress without waiting through the failed record's retry backoff. The initial handler and infrastructure can still take time, and retrying records can be overtaken. There is no zero-delay or cross-topic ordering guarantee. Exact generated retry topic names are managed by Spring Kafka; the source configures `-retry` and `-dlt` suffixes, rather than a manually provisioned production topology. See the [event catalog](event-catalog.md).

Capture/refund notifications are acknowledged and skipped by this authorization consumer; their state and journals already committed in synchronous service transactions.

## Crash windows and limits

| Interruption | Recovery behavior on a later valid delivery |
| --- | --- |
| Before preparation commits | The CREATED payment has no committed new intent; preparation can begin again. |
| After preparation, before definitive simulator outcome | PROCESSING and its durable intent remain; evaluation can resume. |
| After simulator outcome commits, before finalization | The retained result is reused; finalization can complete. |
| After finalization, before acknowledgment | Final-state/result/consumption evidence is validated; no new simulator authorization is created. |
| Historical PROCESSING with no durable operation | Ambiguous legacy activity is quarantined; no provider outcome is invented. |

No background recovery sweep or DLT replay API is installed. Recovery requires an eligible later delivery; broker retention loss, unavailable evidence or database corruption can prevent it. [Authorization reconciliation](reconciliation.md) can report outstanding finalization but never performs it.

## Dead-letter diagnostics and outbox failures

`handleDltMessage` persists diagnostic payload/header/exception evidence and acknowledges only after persistence succeeds. It does **not** mark a payment FAILED. Timeout, DLT routing and consumption evidence are transport observations, not financial truth. Definitive FAILED comes from the persisted simulator rejection and normal finalization. Malformed or conflicting messages cannot safely establish a payment outcome.

ADMIN can read `/v1/events/dlt` and filter by payment ID. Browser responses redact payload/exception text and expose only an allowlisted correlation header; see [DltAdminController](../backend/src/main/java/com/lky/kaipay/dlt/api/DltAdminController.java). There is no replay/repair mutation endpoint.

The separate [outbox publisher](../backend/src/main/java/com/lky/kaipay/outbox/service/OutboxEventPublisher.java) retains retryable publication failures with due times and capped exponential backoff. Record-too-large failures are quarantined. A crash after Kafka accepts but before the outbox database commit can publish a duplicate. `SKIP LOCKED` avoids waiting on already-claimed rows; it does not eliminate all contention or guarantee FIFO. See [architecture reality check](architecture-reality-check.md) for scheduling details.

Source-backed regressions cover [recovery](../backend/src/test/java/com/lky/kaipay/payment/AuthorizationRecoveryIntegrationTest.java), [redelivery](../backend/src/test/java/com/lky/kaipay/payment/consumer/PaymentGatewayRedeliveryIntegrationTest.java), [main-partition progress](../backend/src/test/java/com/lky/kaipay/payment/consumer/PaymentHeadOfLinePartitionIntegrationTest.java) and [DLT persistence failure](../backend/src/test/java/com/lky/kaipay/payment/consumer/DltPersistenceRecoveryIntegrationTest.java). These establish the tested scenarios, not production availability.
