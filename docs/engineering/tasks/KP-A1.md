# KP-A1 — Distributed Correlation ID Propagation

## Status, scope, and authority

Implemented, independently reviewed and verified; prepared for gated commit/publication. Direct user approval, 2026-10-12 (Asia/Kuala_Lumpur), authorizes this task second after KP-ERR-01, including focused commit/push to `origin/master` only after all workflow gates. Approval and acceptance criteria are recorded in local-only `docs/engineering/QUEUE.md`. Initial clean `master` and live remote: `253230f4d2d8ccc67194beb1a18c34edebc8bac8`; task 1's exact published hash was checked with `git ls-remote` before starting. Stop at a mandatory failed gate or after this task's verified publication; no additional task or deployment permission.

Acceptance: stable correlation across applicable HTTP, persisted outbox, Kafka and consumers; safe asynchronous boundary propagation; missing/invalid/legacy handling; no MDC leakage across request/worker reuse; preserve payment/accounting/event ownership and unrelated payload contracts; meaningful continuity verification; explicit tracing limitations. Significant unresolved compatibility/security decisions require a stop. Protected documents and Learning Lab excluded.

## Problem and evidence

VERIFIED by source at the initial HEAD: no ingress correlation filter or MDC/logging pattern. Three service outbox writers persist only merchant ID in existing JSONB headers. Publisher discards those headers with the three-argument Kafka send. Main and DLT consumers do not establish correlation context. CORS does not permit or expose the correlation header. DLT generic metadata decoding treats four/eight bytes as numbers. The backlog's envelope-change example is a proposal, not needed by the actual schema.

Read-only investigator `/root/err_investigation` inspected HTTP, persistence, publisher, retry/DLT, tests and boundary risks. Main agent independently inspected those source paths and accepted a narrow metadata-only design.

## Decision and implementation

Main-agent design approval:

- Canonical HTTP/Kafka header `X-Correlation-Id`; persisted outbox key `correlationId`; owned MDC key `traceId`.
- Identifier: 1–64 ASCII characters, initial alphanumeric, remaining alphanumeric/dot/underscore/hyphen. Replace missing/invalid/ambiguous HTTP values with a UUID, return the effective response header, never log rejected raw metadata. CORS permits/exposes the header for the existing allowed origins. Request attribute retains it across explicitly registered request/async/error dispatches.
- Capture correlation in all three outbox writers using valid current context or the event UUID for direct service calls. Existing envelope/payload, schema and transaction boundaries stay intact.
- Publisher validates stored metadata, accepts safe legacy `traceId` only when canonical metadata is absent, otherwise falls back to stable outbox row UUID. Copy existing headers and persist canonical metadata; never inherit scheduler context. Publish the same topic/key/value with the canonical correlation header. Scope MDC around the entire row's send, retry/quarantine, persistence and logging, with cleanup on every return/continue/break/exception.
- Consumers use the last canonical Kafka header, validate bounded ASCII bytes, and normalize to exactly one effective header before business processing. Missing/invalid values use a deterministic UUID from topic/partition/offset; Spring forwarding must retain the normalized header across retries/DLT. Scope both main and DLT processing, including acknowledgment, early exits and failures. Persist the DLT effective header explicitly as a string, including four/eight-character values.
- Restore prior owned MDC value on scope exit and preserve unrelated caller MDC entries. Do not propagate MDC automatically into arbitrary executors. Add correlation to the application log pattern.

Implementation specialist `/root/err_implementation` owns production/unit changes; `/root/err_verification_audit` owns separate new real HTTP/PostgreSQL/Kafka integration coverage. Main agent owns design, documentation, execution and delivery. No overlapping writes.

Exact backend paths, relative to repository root:

- `backend/src/main/java/com/lky/kaipay/common/config/CorrelationFilterConfiguration.java`
- `backend/src/main/java/com/lky/kaipay/common/config/WebConfig.java`
- `backend/src/main/java/com/lky/kaipay/common/observability/CorrelationContext.java`
- `backend/src/main/java/com/lky/kaipay/common/observability/CorrelationIdFilter.java`
- `backend/src/main/java/com/lky/kaipay/outbox/service/OutboxEventPublisher.java`
- `backend/src/main/java/com/lky/kaipay/payment/consumer/PaymentProcessingConsumer.java`
- `backend/src/main/java/com/lky/kaipay/payment/service/PaymentService.java`
- `backend/src/main/java/com/lky/kaipay/refund/service/RefundService.java`
- `backend/src/main/resources/application.yaml`
- `backend/src/test/java/com/lky/kaipay/common/observability/CorrelationContextUnitTest.java`
- `backend/src/test/java/com/lky/kaipay/common/observability/CorrelationIdFilterUnitTest.java`
- `backend/src/test/java/com/lky/kaipay/common/observability/CorrelationPropagationIntegrationTest.java`
- `backend/src/test/java/com/lky/kaipay/outbox/service/OutboxEventPublisherUnitTest.java`
- `backend/src/test/java/com/lky/kaipay/outbox/service/OutboxRetryIntegrationTest.java`
- `backend/src/test/java/com/lky/kaipay/payment/consumer/PaymentConsumerCorrelationUnitTest.java`

Publication documentation paths: this report, `docs/event-catalog.md`, and task 1's delivery-reference update in `docs/engineering/tasks/KP-ERR-01.md`. Queue/index/architecture/backlog remain local-only; no ignore-policy reversal. Existing publisher test mock changes adapt the Kafka `ProducerRecord` overload while preserving previous assertions.

## Review and verification

Independent adversarial reviewer `/root/err_investigation`, distinct from both implementers, reviewed all 15 backend paths (seven new) against `253230f`, surrounding business/transaction/retry paths, new tests and documentation. Approved subject to verification, with no functional, security or compatibility blocker. Main agent inspected the actual diff and assertions and accepted the findings: all business branches, transaction boundaries, Kafka key/value, retry policy and event ownership are preserved; entire row/listener/dispatch scopes restore MDC on exits. Reviewer bounded servlet redispatch/cleanup as mainly unit evidence and CORS as source inspection without browser preflight evidence. Focused and full JDK 21 PostgreSQL/Kafka verification passed; independent artifact audit and final documentation re-review passed. Final review found no delivery blocker; the freshness range below is explicitly file modification time, not an XML timestamp attribute.

Environment: Windows/PowerShell, Maven 3.9.16, explicit `JAVA_HOME=C:/Program Files/Java/jdk-21` and PATH prepend; process Java 21.0.2. Real fixtures `postgres:16-alpine` and `confluentinc/cp-kafka:7.6.0`, Docker 29.4.3, Hibernate 6.6.8.Final, Spring Kafka 3.3.3. All Maven commands from `C:/LKY_Project/KaiPay/backend`, no skipping flags. Context7 documented structured exception/filter/header APIs; pinned-source compilation and actual broker tests bound the implementation evidence.

| Gate | Actual evidence |
| --- | --- |
| Focused | `mvn -o -Dtest=CorrelationContextUnitTest,CorrelationIdFilterUnitTest,PaymentConsumerCorrelationUnitTest,OutboxEventPublisherUnitTest,CorrelationPropagationIntegrationTest,OutboxRetryIntegrationTest,OutboxKafkaOutageIntegrationTest,PaymentEventRoutingIntegrationTest,RefundIntegrationTest,PaymentIntegrationTest,PaymentCaptureIntegrationTest test`: exit 0, 109 tests, zero failures/errors/skips, 1:07 min, completed 2026-10-12 16:40:26 +08; `scratch/kp-a1-focused.log`. |
| Real continuity | Eleven `CorrelationPropagationIntegrationTest` invocations use actual HTTP/JSONB/Kafka/listeners and synchronous thread-safe MDC snapshots; all three writers, unchanged Kafka payload, foreign-thread gateway context, three retry attempts, normalization before retry, DLT four/eight-character strings, restored publisher caller context, and safe legacy metadata. No replaced application beans. |
| Full affected module | `mvn -o verify`: exit 0, 298 tests, zero failures/errors/skips, 1:18 min, completed 2026-10-12 16:42:43 +08; jar and Spring Boot repackage succeeded; `scratch/kp-a1-verify.log` and `backend/target/surefire-reports/`. |
| Frontend | Not applicable: no frontend code/build or response/domain payload changes; optional headers and existing-origin CORS support are backend changes. No browser preflight test was run. |

Unit assertions establish bounded ASCII validation, duplicate policy, absent/null/non-string/unsafe metadata, nested and reused scopes, preserved unrelated MDC, mock servlet async/error/nested-error redispatch, consumer calls/acknowledgments under context, early-return and thrown/caught-failure cleanup, publisher context during send/retry/quarantine/save, and DB-error/interruption exit restoration. Existing real PostgreSQL/Kafka outage/retry/locking, lifecycle event ownership, payment/refund idempotency and accounting assertions remain in the focused/full gates. No backend change after independent review.

Independent verification audit by `/root/err_investigation`: 67 fresh Surefire XML files from the same final JVM command, XML file modification times 16:41:29.0189277–16:42:42.0938981 +08; declared totals and actual testcase nodes both 298; no failure/error/skipped nodes. All report Java 21.0.2/backend working directory and no selective-test or skip properties. Auditor also confirmed 109 focused tests and 11 real continuity cases in both runs, and observed the short `AB12` ID across HTTP/publisher/main/retry1000/retry2000/DLT logs plus legacy alias continuity. Main agent inspected actual process exit and log results and accepted this audit with the explicit evidence boundaries below.

## Limitations and follow-ups

Correlation is per operation, not automatically a payment-lifetime trace. A later capture/refund request gets its own ID unless the caller reuses one. Idempotent HTTP replays return the current request ID while retaining the original event's creation ID, without new events or metadata rewriting. Historical events without ingress metadata cannot reconstruct their original HTTP ID. Application scopes do not establish complete framework/Kafka producer callback/external gateway tracing, W3C spans, or arbitrary executor propagation. Current controllers are synchronous; servlet redispatch support does not imply propagation into user-created async tasks. IDs are diagnostic metadata, not authentication, authorization or idempotency keys. Older publishers drop correlation headers and older consumers do not set this context; mixed-version deployments preserve business compatibility but cannot guarantee full correlation continuity until all applicable components use this change. Deployment was not performed or authorized by this batch.

## Git delivery and stopping point

Commit/publication pending after successful review/focused/full backend gates. Exact publication scope is 15 backend paths plus three documentation paths listed above. Task 1's delivery references were updated during this authorized documentation phase. Final Git/staging/remote gates remain; no push before they pass. Identify this report-bearing commit from Git history and the final consolidated batch report rather than recursively adding its own hash. Stop after verified remote publication; other follow-ups remain unapproved.
