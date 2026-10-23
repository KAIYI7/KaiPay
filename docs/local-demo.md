# Supervised local demonstration

KaiPay demonstrates simulated payments in a local environment. There is no hosted public demo; Oracle hosting is postponed. External webhook dispatch remains disabled. This guide explains the existing merchant and operator workflows without requiring a public tunnel, a bank account or shared credentials.

## Prepare a disposable environment

Use a fresh local database with synthetic data, separate from any retained financial dataset. Starting the backend applies pending V1–V11 migrations to its configured database; do not point a demonstration at retained data without a separate upgrade plan. Follow the [root setup instructions](../README.md#8-running-the-project) and [backend configuration and individual account provisioning](../backend/README.md). Use JDK 21, the explicit `local` profile and loopback access. Keep Kafka/data management ports private.

The operator provisions a MERCHANT account with an assigned merchant and, separately, an ADMIN account for diagnostics. Startup seeds synthetic merchants/customers but creates no login accounts. Use a synthetic customer belonging to the selected merchant; the sandbox offers hints for known seed data and accepts a customer UUID for other provisioned merchants. Obtain each account privately from the operator; never embed credentials in screenshots, URLs or this guide.

## Merchant walkthrough

1. Sign in as a merchant. The merchant selector lists only assigned memberships. **Dashboard** shows MYR ledger projections and recent payments, not real funds available for payout.
2. Open **Testing Sandbox**. Submit **RM50.00** for an owned synthetic customer. The backend commits `CREATED` plus an outbox event; Kafka drives `PROCESSING` and then simulator `AUTHORIZED`. Open the payment from the result and refresh if authorization is still pending.
3. Before changing the sandbox inputs or generating a new key, use its idempotent replay action. The same key and request return the original creation response and payment ID; that cached response may still say `CREATED`. Read current status on the payment detail page. The conflict action reuses the key with a changed amount and should produce `409`, without another payment.
4. On the authorized payment, use **Capture Funds** and confirm the capture. This posts the simulated capture journal synchronously; it does not contact a real bank or settle funds. Then use **Issue Refund** for RM20.00, inspect `PARTIALLY_REFUNDED`, and refund the remaining RM30.00 to reach `REFUNDED`.
5. Open **Ledger & Accounts** and inspect the capture and refund journals. Each journal balances debits and credits. For this example the capture fee is RM1.75; the two refund fee reversals total RM1.45. The fixed RM0.30 remains as platform revenue. These are demonstration formulas, not provider pricing. Other merchant activity can affect the dashboard's aggregate balance.
6. Open **Reconciliation**. It displays stored simulated authorization reports created manually by the operator; viewing it never runs reconciliation. Capture/refund accounting is outside this page's scope. An empty list means no reports exist for this scope, not that all payments are healthy.
7. Open **Webhooks**. This is read-only enrollment/delivery history. An unenrolled merchant sees no endpoint and no historical backfill. Enrollment and runtime dispatch are distinct: an enrolled endpoint alone does not prove the dispatcher is enabled. External dispatch remains disabled for this demonstration.
8. Sign out before switching identities. A different merchant sees only its assigned resources; an ADMIN account uses separate diagnostics and does not impersonate the merchant.

Optional failure scenarios create additional synthetic payments:

| Amount | Simulator behavior | What to explain |
| --- | --- | --- |
| RM8,888.00 | Two unresolved evaluations, then approval on the third | Durable evaluation count and delayed retry; timing depends on the local environment |
| RM7,777.00 | Persistent unresolved timeout | Retained delivery retries exhaust to DLT; payment remains `PROCESSING` |
| RM6,666.00 | Definitive rejection without acceptance | `FAILED` and completion marker commit before diagnostic DLT routing |
| RM9,999.00 | Business decline | `DECLINED`; no capture or ledger posting |

These triggers belong to the [durable local simulator](../backend/src/main/java/com/lky/kaipay/payment/service/acquirer/SimulatedAuthorizationOperation.java). A DLT record is diagnostic evidence, not a replay control or proof of a financial failure. Do not repair states or edit database rows to make the demonstration appear successful.

## Operator walkthrough

Sign in separately as ADMIN. **Outbox Stream** shows event status, retry timing and quarantine; **DLT Explorer** shows safe diagnostic metadata. Raw payloads and exception details are omitted in the browser. A clean or empty diagnostic list is not a system-health certificate. **Ledger & Accounts** provides global journal diagnostics; **Reconciliation** requires an explicit merchant UUID.

The two manual assessment tools serve different purposes:

| Tool | Reads and output | Demonstration boundary |
| --- | --- | --- |
| [Authorization reconciliation](reconciliation.md) | Compares payment/consumer evidence with durable simulator outcomes; persists immutable reports for merchant/admin read-only views | Operator CLI, one current snapshot and a bounded payment creation cohort. Choose the interval/grace as documented; very recent payments can be outside coverage. No capture/refund provider assessment or repair. |
| [Internal accounting audit](internal-accounting-audit.md) | Checks retained capture/refund sources, journals, ownership and currency; emits transient JSON | Standalone SELECT-only operator CLI, no HTTP/UI, persistence or scheduler. Inspect all classifications and unassessed records. Balanced structural relationships do not certify fee policy or real settlement. |

Use the existing contracts for invocation and least-privilege database grants. Keep database credentials and report exports private. Operator database access is separate from browser account access; neither command sends a payment, enables webhooks or repairs a finding.

Signed webhook delivery has isolated local test coverage for signatures, retries, pinned transport and crash recovery. This walkthrough shows the disabled/unenrolled boundary; it does not send an external webhook or establish deployment egress safety. See the [webhook contract](webhooks.md) and [test sources](../backend/src/test/java/com/lky/kaipay/webhook/delivery).

## Evidence and cleanup

A supervised local session has exercised merchant creation/capture/refund, role-separated diagnostics and stored authorization reports. The steps above describe a repeat procedure, not a hosted service or a promise that every failure class has been demonstrated in a browser. Component tests with mocked HTTP, real PostgreSQL/Kafka integration tests and human observation establish different kinds of evidence; see [verification sources](../README.md#9-automated-testing-strategy).

The [committed screenshots](../README.md#7-visual-portfolio-assets) are historical illustrations with obsolete currency, navigation and diagnostic details. Current private acceptance captures, logs and engineering records are not public assets. When presenting your own run, use synthetic data and avoid credentials, raw payloads, private references and unrelated browser content.

Sign out and stop only the demonstration processes/resources you created. Dispose of a synthetic database only after checking its ownership; never reset an existing volume or financial dataset as a demo cleanup shortcut. No hosting, tunnel, external dispatch or data repair is part of this walkthrough.
