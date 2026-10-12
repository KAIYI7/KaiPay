# KP-ERR-01 — Database Exception Classification

## Status, scope, and authority

Implemented, independently reviewed and verified; prepared for gated commit/publication. Direct user approval on 2026-10-12 (Asia/Kuala_Lumpur), recorded in the local-only `docs/engineering/QUEUE.md`, authorizes this task first, with focused commit and push to `origin/master` only after review and verification. Initial clean `master` and live remote HEAD: `0eb0e6770d6aed51b6e499745548f667e57f36af`. Stop on any mandatory gate; KP-A1 waits for verified publication. Protected documents and Learning Lab excluded.

Acceptance: preserve genuine identified idempotency HTTP 409 and source-supported conflicts; do not classify arbitrary integrity failures as idempotency/409; keep HTTP errors free of SQL, constraints, database and sensitive details; cover known and unrelated violations; verify payment/refund replay and rollback; avoid a general error redesign.

## Problem and evidence

VERIFIED by baseline source inspection: `GlobalExceptionHandler.handleDataIntegrityViolationException` unconditionally reports an idempotency conflict. V1 defines `uk_merchant_idempotency` and `pk_idempotency_records`; V5 defines `uk_refunds_idempotency`. Other unique, foreign-key, not-null and check constraints have no source-supported HTTP 409 contract. Explicit `IdempotencyConflictException` remains an established 409 contract. Existing payment concurrency and refund cross-payment uniqueness tests require 201/replay or 409; refund tests assert full loser rollback with real PostgreSQL blocking evidence.

Read-only investigator: `/root/err_investigation`. Main agent inspected schema, service transaction flow, handler and test assertions and accepted these conclusions.

## Decision and implementation

Main-agent approved bounded design: require structured Hibernate constraint metadata with SQLState `23505` and an exact allowlisted idempotency constraint name; traverse wrapped causes safely without message matching. Unknown/missing metadata returns the existing sanitized generic 500. No schema or API payload redesign. Remove payment creation's broad integrity catch and failed-transaction lookup so failures propagate for rollback and classification. Retain committed replay, transaction boundaries, ledger and event ownership.

Changed backend files (paths relative to repository root):

- `backend/src/main/java/com/lky/kaipay/common/api/GlobalExceptionHandler.java`
- `backend/src/main/java/com/lky/kaipay/common/exception/DatabaseIntegrityClassifier.java`
- `backend/src/main/java/com/lky/kaipay/payment/service/PaymentService.java`
- `backend/src/test/java/com/lky/kaipay/common/api/GlobalExceptionHandlerUnitTest.java`
- `backend/src/test/java/com/lky/kaipay/common/exception/DatabaseIntegrityClassifierUnitTest.java`
- `backend/src/test/java/com/lky/kaipay/common/exception/DatabaseIntegrityClassifierIntegrationTest.java`
- `backend/src/test/java/com/lky/kaipay/payment/service/PaymentCreationIntegrityUnitTest.java`

This report is the eighth publication path. Local-only queue/index/architecture were updated without reversing the baseline publication policy.

## Review and verification

Independent adversarial reviewer `/root/err_investigation` did not implement any changes. Reviewed the stable seven-file backend candidate against `0eb0e67`, schema and transaction context, new assertions and existing payment/refund races. No functional blockers. Main agent inspected the actual diff and assertions and accepted the review. Reviewer documentation finding: historical queue's assertion of no backend approval conflicted with the new batch; scoped it to the exhausted foundation approval. Reviewer correctly bounded native-SQL metadata evidence separately from HTTP race/rollback evidence.

Environment: Windows/PowerShell; Maven 3.9.16; explicit process `JAVA_HOME=C:/Program Files/Java/jdk-21` and PATH prepend; `mvn -version` confirmed Java 21.0.2. Docker 29.4.3; fixtures `postgres:16-alpine` (observed server 16.15), `confluentinc/cp-kafka:7.6.0`; pinned Hibernate 6.6.8.Final and PostgreSQL JDBC 42.7.5. Docker/network sandbox denial resolved through permitted elevated execution. No dependency/build-config changes or skipped integration substitutions.

All Maven commands below ran from `C:/LKY_Project/KaiPay/backend`, without test-skipping flags. Local ignored logs under `scratch/` retain exact output.

| Gate | Actual evidence |
| --- | --- |
| Focused unit/API/payment/refund/capture | `mvn -o -Dtest=DatabaseIntegrityClassifierUnitTest,GlobalExceptionHandlerUnitTest,DatabaseIntegrityClassificationIntegrationTest,PaymentCreationIntegrityUnitTest,PaymentIntegrationTest,RefundIntegrationTest,PaymentCaptureIntegrationTest test`: exit 0, 65 tests, no failures/errors/skips, 41.282s; `scratch/kp-err-focused.log`. The metadata class name was mistyped in this selection, so it was separately executed below; it is not counted as coverage from this command. |
| Actual PostgreSQL metadata | `mvn -o -Dtest=DatabaseIntegrityClassifierIntegrationTest test`: exit 0, 5 tests, no failures/errors/skips, 23.512s; `scratch/kp-err-metadata.log`. Native inserts hit all three named constraints plus unrelated customer uniqueness and amount check; assertions inspect actual SQLState/name and explicitly invoke HibernateJpaDialect translation and advice mapping. Failed fixture transactions roll back. |
| Full affected module | `mvn -o verify`: exit 0, 247 tests, no failures/errors/skips, 56.545s, completed 2026-10-12 16:24:57 +08; jar and Spring Boot repackage succeeded; `scratch/kp-err-verify.log` and `backend/target/surefire-reports/`. |
| Frontend | Not applicable: no frontend code, payload shape, or build changes; HTTP status correction is intentional and backend API assertions cover it. |

The focused payment/refund suites assert existing replay/payload-conflict contracts, merchant scoping, one payment/outbox under competition, PostgreSQL-observed blocking, losing refund transaction rollback and accounting snapshots. Capture asserts replay without duplicate outbox/journal entries. Unit HTTP assertions inspect serialized response bodies for SQL, constraint, SQLState and secret leakage. Synthetic foreign-key/not-null checks are distinct from real PostgreSQL uniqueness/check evidence.

Independent read-only verification auditor `/root/err_verification_audit` inspected 63 fresh Surefire XML files from the single final JVM run: declared totals and actual testcase nodes both 247, zero failure/error/skipped nodes, Java 21.0.2 and backend working directory, no selective-test property. XML timestamps span 16:24:04.857–16:24:55.440 +08. Auditor inspected assertions and observed real payment `23505` collisions plus sanitized 409 in this run; payment fixture remains nondeterministic. Main agent confirmed process exit 0 and build success and accepted the evidence with the bounds below.

## Limitations and follow-ups

Classification intentionally depends on structured Hibernate metadata and current named constraints; JDBC-only failures, absent names and future unknown constraints safely fall back to 500. Constraint changes require an explicit contract and regression update. Server-side diagnostic logging can contain exception details under existing logging behavior; HTTP responses for these failures are sanitized. These checks do not establish durable external-bank idempotency, authorization crash recovery, or general distributed exactly-once behavior. Payment-save/outbox-save failure propagation is mocked unit evidence, not a forced real outbox failure. Existing parallel payment HTTP tests permit all 201 responses and therefore do not prove a unique collision on every run; deterministic refund races separately prove constraint/lock rollback behavior. No unresolved design/review/verification blocker; publication pending.

## Git delivery and next step

No task commit or publication yet. Engineering queue/index and architecture/backlog are local-only after the baseline documentation-policy commit; do not force-add those records. New task report is intended for focused publication. Continue only to KP-A1 after remote verification.
