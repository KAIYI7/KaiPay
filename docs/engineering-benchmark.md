# KaiPay engineering trade-offs

This is a source-based design comparison, not a performance benchmark or a claim about the private implementations of commercial payment providers. KaiPay has no published throughput, latency, availability or compliance certification. Its current platform is Java 21 and Spring Boot 4.1.1; see [backend/pom.xml](../backend/pom.xml).

| Area | Delivered choice | Trade-off and current limit |
| --- | --- | --- |
| Application topology | Modular monolith with one backend and PostgreSQL. | Local service calls and shared transactions keep operation small. Independent scaling and failure isolation are limited. No evidence requires microservices. |
| Event publication | Transactional outbox, due-row polling and `FOR UPDATE SKIP LOCKED`. | Domain state and publication intent commit together. Sequential broker waits hold the poller transaction open; duplicates remain possible after broker acceptance. CDC or asynchronous batching would add complexity and need measured justification. |
| Authorization recovery | Durable simulator operation committed separately between payment preparation and finalization. | Retained outcomes survive process restarts and can be reused on valid redelivery. The simulator shares PostgreSQL; this does not prove real-provider recovery. |
| Retry isolation | Spring Kafka retry topics plus persisted DLT diagnostics. | Main-topic progress need not wait for retry backoff. Retries can reorder records; DLT routing does not imply payment rejection. No replay API or background recovery sweep. |
| API idempotency | PostgreSQL request hash/response records and unique constraints. | Durable state is colocated with payment transactions. Redis is not used for the idempotency path; a cache would require measured benefit and consistency/expiry design. |
| Accounting | Balanced capture/refund journals and derived balances. | More records and aggregate reads than a mutable balance column. No settlement, payout or bank reconciliation. |
| Money representation | New activity in MYR integer minor units, with explicit fee rounding. | Historical currency evidence remains unchanged. Integer storage does not eliminate every rounding or overflow concern. |
| Access | Individual server sessions, CSRF and resource/membership checks. | Sessions are in memory. ADMIN reads operational resources without merchant impersonation; no public registration. |
| Observability | Validated correlation IDs across HTTP, outbox, Kafka and scoped MDC. | Helps follow operations; not complete distributed tracing or cryptographic audit evidence. |
| Webhooks | Atomic intents, versioned signatures, bounded retries and merchant read-only views. | External runtime delivery is disabled. Enabling it needs a separate operational change; implemented transport is exercised by test harnesses. |
| Assessment | Stored authorization comparison and transient operator accounting audit. | Distinct scopes; neither repairs records or certifies financial completeness. |

## Why retain these choices

The [outbox publisher](../backend/src/main/java/com/lky/kaipay/outbox/service/OutboxEventPublisher.java), [authorization recovery service](../backend/src/main/java/com/lky/kaipay/payment/service/AuthorizationRecoveryService.java) and [ledger service](../backend/src/main/java/com/lky/kaipay/ledger/service/LedgerService.java) demonstrate transaction boundaries, duplicate handling and accounting checks with a small deployment footprint. They offer useful engineering examples without adding a cache, workflow engine or independent services solely to resemble a larger company.

PostgreSQL uniqueness and row locking remain the durable coordination mechanisms. A fast cache or distributed lock would introduce another failure boundary; no workload evidence here establishes that it is needed. A domain outbox records successful business transitions, not all rejected security attempts. Correlation logs and diagnostic records do not establish PCI-DSS, SOC 2 or immutable forensic compliance.

## Evidence and assessment limits

Consult the [architecture reality check](architecture-reality-check.md) and [failure handling](failure-handling-and-retries.md) for implementation/test links. [Authorization reconciliation](reconciliation.md) assesses retained simulator outcomes against payment authorization evidence; [internal accounting audit](internal-accounting-audit.md) examines retained capture/refund/journal structure through an operator CLI. Matching records do not establish bank acceptance, clearing, settlement or correct fee allocation.

The application has a supervised local demonstration. Hosting remains postponed and external webhook delivery remains disabled. The tests and local demonstration are bounded evidence, not production scale measurements or external security certification.
