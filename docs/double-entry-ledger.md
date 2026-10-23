# Double-entry ledger and accounting invariants

KaiPay records simulated capture/refund accounting in journals and ledger entries rather than updating a stored balance scalar. New activity uses MYR integer minor units: 100 sen = RM1.00. This is an application accounting model, not a real acquiring, clearing, settlement or payout integration, and no accounting-standard or security certification is claimed.

## Posting invariants

[LedgerService](../backend/src/main/java/com/lky/kaipay/ledger/service/LedgerService.java) validates each new journal's nonempty entries, account and entry type, positive amount and matching MYR account/entry currency. It checks that total debits equal total credits and rejects an imbalance with `UnbalancedJournalException`, rolling back the transaction. Positive row checks also exist in the [ledger migration](../backend/src/main/resources/db/migration/V5__create_ledger_schema.sql).

The cross-row balance check is application validation, not a database constraint protecting against arbitrary privileged SQL. Journals/entries are append-only in these application workflows; this does not make retained records tamper-proof. Integer storage avoids representing monetary amounts as fractional database floating-point values, while fee calculation still uses explicit multiplication and rounding.

| Account type | Normal balance | Balance calculation |
| --- | --- | --- |
| ASSET / EXPENSE | Debit | Debits minus credits |
| LIABILITY / REVENUE / EQUITY | Credit | Credits minus debits |

## Chart of accounts

[AccountProvisioningService](../backend/src/main/java/com/lky/kaipay/ledger/service/AccountProvisioningService.java) creates compatible accounts when needed and rejects an existing account with incompatible currency.

| Account number | Type / scope | Role in this simulation |
| --- | --- | --- |
| `1000-CUSTOMER-RECEIVABLE` | ASSET, system | Debited for captured gross, credited for refunds. |
| `2000-MERCHANT-{UUID}-LIABILITY` | LIABILITY, merchant | Credited for captured net, debited for the merchant refund share. |
| `4000-PLATFORM-FEE-REVENUE` | REVENUE, system | Credited for capture fees, debited for the refunded percentage component. |

These names represent simulated obligations, not evidence of funds at a bank or an implemented settlement/payout workflow. Historical non-MYR records remain in their original currency and are not relabelled or converted.

## Capture and refund examples

A capture commits payment state, its journal and outbox notification in one synchronous [PaymentService](../backend/src/main/java/com/lky/kaipay/payment/service/PaymentService.java) transaction, together with an enrolled webhook intent when applicable. The authorization consumer does not post this journal later.

The implemented capture formula in minor units is:

```text
fee = min(gross, round(gross * 0.029) + 30)
merchantNet = gross - fee
```

For RM100.00 (10,000 sen), the fee is 320 sen and merchant net is 9,680 sen:

| Account | Debit (MYR) | Credit (MYR) |
| --- | --- | --- |
| Customer receivable | RM100.00 | — |
| Merchant liability | — | RM96.80 |
| Platform fee revenue | — | RM3.20 |
| Total | RM100.00 | RM100.00 |

[RefundService](../backend/src/main/java/com/lky/kaipay/refund/service/RefundService.java) commits the refund, payment state, reversing journal and outbox notification together, with an enrolled webhook intent when applicable. The percentage component is reversed per refund; the capture's fixed fee is not explicitly reversed:

```text
feeRefund = min(refundGross, round(refundGross * 0.029))
merchantRefundShare = refundGross - feeRefund
```

A RM50.00 partial refund of that capture produces:

| Account | Debit (MYR) | Credit (MYR) |
| --- | --- | --- |
| Merchant liability | RM48.55 | — |
| Platform fee revenue | RM1.45 | — |
| Customer receivable | — | RM50.00 |
| Total | RM50.00 | RM50.00 |

A single full RM100.00 refund reverses RM2.90 of fee and debits the merchant RM97.10. Against the original RM96.80 credit, the merchant position is minus RM0.30. This illustrates the configured simulator policy; it does not reimburse real gateway costs.

The minus-30-sen example is not a universal formula for all payment sizes or refund splits. Capture fees are capped at gross for small payments, and independently rounded partial refunds can differ from a single full refund. Zero-value journal lines are omitted, so a journal does not always have exactly three entries. Each actual journal must remain balanced.

## Concurrency and balance projections

Refunds lock the owned parent payment with `PESSIMISTIC_WRITE` before checking its refundable state and completed refund total. Competing refund operations on that payment serialize, so a later request reassesses the remaining amount after the earlier transaction. Idempotency and database constraints provide additional duplicate-request protection. See [RefundService](../backend/src/main/java/com/lky/kaipay/refund/service/RefundService.java) and [PaymentRepository](../backend/src/main/java/com/lky/kaipay/payment/repository/PaymentRepository.java).

Balances are derived through [LedgerEntryRepository](../backend/src/main/java/com/lky/kaipay/ledger/repository/LedgerEntryRepository.java) aggregate queries using the account's normal balance. The service rejects conflicting stored account/entry currency. This is a current projection from retained entries, not a settlement statement, cached balance guarantee or large-history performance benchmark.

## Two distinct assessment capabilities

[Authorization reconciliation](reconciliation.md) compares retained simulator authorization evidence to payment records and stores immutable bounded reports visible to merchant/ADMIN sessions. It does not assess capture/refund journals.

The [internal accounting consistency audit](internal-accounting-audit.md) is a separate transient read-only operator CLI. It checks retained capture/refund/journal/entry relationships, ownership, role, currency and source gross amounts. It adds no UI, HTTP endpoint, persistent report tables or scheduler. Fee allocation is outside its assessment: a balanced but incorrectly split fee can pass those structural checks. Neither assessment repairs financial records or proves provider acceptance or settlement.

[LedgerFinancialInvariantsIntegrationTest](../backend/src/test/java/com/lky/kaipay/ledger/service/LedgerFinancialInvariantsIntegrationTest.java) exercises bounded database-backed posting scenarios. Tests establish their assertions, not a universal proof of accounting correctness or privileged-write immutability.
