# KaiPay engineering journal: distributed systems lessons

These lessons explain implemented choices and their limits. KaiPay processes simulated MYR payments; references to provider outcomes below mean the local durable simulator, not a bank integration.

## 1. Anchor publication intent in the business transaction

Writing a payment and publishing Kafka directly are separate commits. Publishing before the database commits can produce a message for rolled-back state; publishing only after commit leaves a crash window with no recorded dispatch intent.

[PaymentService](../backend/src/main/java/com/lky/kaipay/payment/service/PaymentService.java) writes the payment, idempotency response and outbox envelope in one transaction. Capture/refund likewise commit their state, journal and notification together. A later [poller](../backend/src/main/java/com/lky/kaipay/outbox/service/OutboxEventPublisher.java) publishes the outbox record. This preserves publication intent; it does not ensure eventual delivery under every failure.

## 2. Row claiming helps concurrency but does not promise FIFO

The [outbox query](../backend/src/main/java/com/lky/kaipay/outbox/repository/PaymentEventOutboxRepository.java) selects due PENDING rows with `FOR UPDATE SKIP LOCKED`, ordered by `COALESCE(next_attempt_at, created_at), created_at, id`. Competing workers skip claimed rows instead of waiting for those row locks. Other lock, database and broker contention can still occur.

Retry due times and skipped locks allow later records to overtake earlier ones. There is no global FIFO guarantee. The [V6 partial index](../backend/src/main/resources/db/migration/V6__add_outbox_retry_scheduling.sql) supports this query without retaining published rows in that index; query plans and performance still depend on actual data and workload. No million-row throughput measurement is claimed.

The publisher waits sequentially for broker results inside its transaction. If Kafka accepts an event but the transaction fails before publication status commits, a later poll can publish it again. Consumer correctness must tolerate that duplicate.

## 3. A consumption marker cannot close every authorization crash window

A marker written after authorization prevents repeat processing after finalization. It cannot, by itself, reveal what happened if the process stopped after a provider accepted but before local finalization.

[AuthorizationRecoveryService](../backend/src/main/java/com/lky/kaipay/payment/service/AuthorizationRecoveryService.java) first commits PROCESSING together with a durable intent. The [simulator](../backend/src/main/java/com/lky/kaipay/payment/service/acquirer/SimulatedAuthorizationTransactionService.java) commits its evaluation separately. Finalization then validates that retained result and commits payment state, marker and any enrolled webhook intent. A later valid delivery can reuse an existing result. Ambiguous historical PROCESSING records without intent are quarantined.

The simulator uses the same PostgreSQL database. This demonstrates restart recovery across distinct local transaction boundaries, not external-bank exactly-once effects. Recovery is delivery-driven; there is no background sweep or DLT replay API.

## 4. Retry isolation trades ordering for progress

The [consumer](../backend/src/main/java/com/lky/kaipay/payment/consumer/PaymentProcessingConsumer.java) moves retryable failures to retry topics with backoff. Later main-topic records can progress without waiting for that backoff, as exercised by the [partition integration test](../backend/src/test/java/com/lky/kaipay/payment/consumer/PaymentHeadOfLinePartitionIntegrationTest.java). Processing and infrastructure still take time; this is not a zero-latency guarantee.

DLT routing records a diagnostic failure. It does not decide that authorization was declined or rejected. The RM7,777.00 timeout scenario remains PROCESSING after exhaustion; only a retained definitive rejection can finalize FAILED. See [failure handling](failure-handling-and-retries.md).

## 5. Integer storage and balanced journals need precise qualifications

New financial activity uses MYR integer minor units: 100 sen = RM1.00. [LedgerService](../backend/src/main/java/com/lky/kaipay/ledger/service/LedgerService.java) validates positive lines, compatible currency and balanced debit/credit totals before saving. Capture uses a rounded 2.9% plus 30-sen fee capped at gross; refund reverses the rounded percentage component. Fee calculation still has explicit rounding, so integer storage alone is not proof against every numeric error.

Refunds lock the parent payment before checking the remaining refundable amount. Journals and entries preserve application history rather than updating a balance scalar. This does not make records tamper-proof against privileged database modification or imply bank settlement. See the [ledger guide](double-entry-ledger.md).

## 6. Separate transport, authorization and accounting evidence

Kafka acknowledgment, DLT and webhook delivery are transport evidence. [Authorization reconciliation](reconciliation.md) compares retained simulator outcomes with payment authorization evidence and stores bounded observations. [Internal accounting audit](internal-accounting-audit.md) checks retained capture/refund/journal relationships through a transient read-only operator CLI. Neither executes financial repair or certifies closed-period completeness.

Individual server sessions, CSRF, merchant memberships and resource ownership enforce browser access; old merchant API-key schema fields do not authenticate browser operations. See [security](security.md). Webhook infrastructure is implemented, but [external delivery remains disabled](webhooks.md). Local demonstrations and tests establish the scenarios observed, without a production availability or compliance claim.
