# KaiPay Double-Entry Financial Ledger & Accounting Invariants

Financial systems must provide provable mathematical consistency, an immutable audit trail, and zero-sum balancing across all participant accounts. KaiPay implements an enterprise-grade double-entry ledger based on GAAP principles, eliminating mutable scalar balance columns in favor of append-only journals.

---

## 1. Core Accounting Theory & Mathematical Invariants

### 1.1. The Fundamental Accounting Equation
In double-entry bookkeeping, every financial transaction must be recorded with equal and offsetting debits and credits:

$$\sum \text{Debits} = \sum \text{Credits}$$

For any atomic Journal $J$ containing $n$ ledger entries:

$$\sum_{i=1}^{n} \text{DebitAmount}(e_i) - \sum_{i=1}^{n} \text{CreditAmount}(e_i) = 0 \quad \text{where } e_i \in J$$

If this condition is not met, `LedgerService` throws `UnbalancedJournalException`, aborting the database transaction and preventing corrupt financial data.

### 1.2. Account Types and Normal Balances

| Account Classification | Normal Balance | Increases With | Decreases With |
| :--- | :--- | :--- | :--- |
| **ASSET** | **DEBIT** | Debit ($+$) | Credit ($-$) |
| **LIABILITY** | **CREDIT** | Credit ($+$) | Debit ($-$) |
| **EQUITY** | **CREDIT** | Credit ($+$) | Debit ($-$) |
| **REVENUE** | **CREDIT** | Credit ($+$) | Debit ($-$) |
| **EXPENSE** | **DEBIT** | Debit ($+$) | Credit ($-$) |

---

## 2. KaiPay Chart of Accounts

KaiPay establishes three fundamental account tiers:

```
+---------------------------------------------------------------------------------------------------+
|                                      CHART OF ACCOUNTS                                            |
+---------------------------------------------------------------------------------------------------+
| Account Number                | Account Name                     | Type      | Scope              |
+-------------------------------+----------------------------------+-----------+--------------------+
| 1000-CUSTOMER-RECEIVABLE      | Customer Funds Receivable        | ASSET     | System-Wide        |
| 2000-MERCHANT-{ID}-LIABILITY  | Merchant Settlement Payable      | LIABILITY | Tenant-Specific    |
| 4000-PLATFORM-FEE-REVENUE     | Platform Fee Revenue             | REVENUE   | System-Wide        |
+-------------------------------+----------------------------------+-----------+--------------------+
```

### 2.1. Account Descriptions
1. **`1000-CUSTOMER-RECEIVABLE` (Asset)**:
   - Tracks unsettled gross funds owed to KaiPay by customer acquiring networks (Visa/Mastercard/Stripe clearing).
   - Debited on payment capture; credited on settlement payout or customer refund.
2. **`2000-MERCHANT-{ID}-LIABILITY` (Liability)**:
   - Represents KaiPay's obligation to pay the merchant tenant for captured sales (net of fees).
   - Credited on payment capture; debited on customer refund or merchant bank payout.
3. **`4000-PLATFORM-FEE-REVENUE` (Revenue)**:
   - Represents transaction processing fee income earned by the KaiPay platform ($2.9\% + \$0.30$).
   - Credited on payment capture; debited (pro-rata) on customer refund.

---

## 3. Financial Journal Entries & Workflows

### 3.1. Payment Capture: 3-Way Balanced Journal

When a payment is captured (`POST /v1/payments/{id}/capture`), KaiPay executes a 3-way balanced journal entry.

#### Fee Calculation Formula
$$\text{FeeCents} = \min\Big(\text{GrossAmount}, \; \text{round}(\text{GrossAmount} \times 0.029) + 30\Big)$$
$$\text{NetMerchantCents} = \text{GrossAmount} - \text{FeeCents}$$

#### Example: $100.00 USD Payment Capture
- **Gross Amount**: $100.00 ($10,000\text{ cents}$)
- **Platform Fee**: $\$100.00 \times 2.9\% + \$0.30 = \$2.90 + \$0.30 = \$3.20$ ($320\text{ cents}$)
- **Net Merchant Amount**: $\$100.00 - \$3.20 = \$96.80$ ($9,680\text{ cents}$)

```
Journal Number: JNL-9A1B2C3D
Source Type:    PAYMENT_CAPTURE
Source ID:      4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b (Payment UUID)
Description:    Payment capture for 4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b

+-------------------------------------+------------+---------------+----------------+
| Account                             | Type       | Debit (USD)   | Credit (USD)   |
+-------------------------------------+------------+---------------+----------------+
| 1000-CUSTOMER-RECEIVABLE            | ASSET      | $ 100.00      | -              |
| 2000-MERCHANT-ACME-LIABILITY        | LIABILITY  | -             | $  96.80       |
| 4000-PLATFORM-FEE-REVENUE           | REVENUE    | -             | $   3.20       |
+-------------------------------------+------------+---------------+----------------+
| TOTALS                                           | $ 100.00      | $ 100.00       |
+--------------------------------------------------+---------------+----------------+
  Verification: Sum(Debits) - Sum(Credits) = $100.00 - $100.00 = $0.00 (BALANCED)
```

---

### 3.2. Partial or Full Refund: Reversing Journal

When a merchant issues a refund (`POST /v1/payments/{id}/refunds`), KaiPay posts an exact reversing journal entry, pro-rating the platform fee and reducing merchant payable liability.

#### Refund Fee Calculation Formula
$$\text{FeeRefundCents} = \min\Big(\text{RefundAmount}, \; \text{round}(\text{RefundAmount} \times 0.029)\Big)$$
$$\text{NetMerchantRefundCents} = \text{RefundAmount} - \text{FeeRefundCents}$$

#### Example: $50.00 USD Partial Refund on the $100.00 Capture
- **Refund Amount**: $50.00 ($5,000\text{ cents}$)
- **Platform Fee Refund**: $\text{round}(\$50.00 \times 2.9\%) = \$1.45$ ($145\text{ cents}$)
- **Merchant Liability Debit**: $\$50.00 - \$1.45 = \$48.55$ ($4,855\text{ cents}$)
- **Customer Funds Receivable Credit**: $\$50.00$ ($5,000\text{ cents}$)

```
Journal Number: JNL-5F6E7D8C
Source Type:    PAYMENT_REFUND
Source ID:      88c21a4f-9e73-4211-88dc-4c8d9e1f2a3b (Refund UUID)
Description:    Payment refund for 4f9d2b1a-8c3e-4d5f-9a1b-2c3d4e5f6a7b, refund 88c21a4f...

+-------------------------------------+------------+---------------+----------------+
| Account                             | Type       | Debit (USD)   | Credit (USD)   |
+-------------------------------------+------------+---------------+----------------+
| 2000-MERCHANT-ACME-LIABILITY        | LIABILITY  | $  48.55      | -              |
| 4000-PLATFORM-FEE-REVENUE           | REVENUE    | $   1.45      | -              |
| 1000-CUSTOMER-RECEIVABLE            | ASSET      | -             | $  50.00       |
+-------------------------------------+------------+---------------+----------------+
| TOTALS                                           | $  50.00      | $  50.00       |
+--------------------------------------------------+---------------+----------------+
  Verification: Sum(Debits) - Sum(Credits) = $50.00 - $50.00 = $0.00 (BALANCED)
```

---

### 3.3. Platform Fee Retention Policy & Full Refund Net Position Mechanics

KaiPay enforces a standard payment industry **Platform Fee Retention Policy** for transaction risk and gateway processing overhead:

1. **On Payment Capture**:
   $$\text{Fee}_{\text{capture}} = \text{round}(\text{amount} \times 2.9\%) + 30\text{ cents}$$
   The total fee comprises a **Variable Fee** ($\text{round}(\text{amount} \times 2.9\%)$) and a **Fixed Transaction Fee** ($30\text{ cents}$).

2. **On Payment Refund**:
   $$\text{FeeReversal}_{\text{refund}} = \text{round}(\text{refundAmount} \times 2.9\%)$$
   The platform refunds the **variable fee component proportionally** to the refund amount, while the **fixed transaction fee ($30\text{ cents}$)** is retained by the platform to cover gateway, scheme, and network processing costs.

3. **Single Payment Full Refund Net Position**:
   When a captured payment is fully refunded:
   $$\text{Net Merchant Position} = (\text{GrossAmount} - \text{Fee}_{\text{capture}}) - (\text{GrossAmount} - \text{FeeReversal}_{\text{refund}}) = -\text{Fixed Fee} = -\$0.30 \; (-30\text{ cents})$$

4. **Multi-Payment Full Refund Cumulative Balance Example**:
   Consider a merchant processing two consecutive payments with full refunds:
   - **Payment 1 ($150.00 / 15,000 cents)**:
     - Capture: Gross = $150.00, Fee = $4.65 ($4.35 variable + $0.30 fixed). Merchant Credited: $145.35.
     - Full Refund ($150.00): Fee Reversal = $4.35. Merchant Debited: $145.65.
     - Retained Fee = $0.30. Net Merchant Position for P1 = $-\$0.30$ (-30 cents).
   - **Payment 2 ($120.00 / 12,000 cents)**:
     - Capture: Gross = $120.00, Fee = $3.78 ($3.48 variable + $0.30 fixed). Merchant Credited: $116.22.
     - Partial Refund 1 ($40.00): Fee Reversal = $1.16. Merchant Debited: $38.84.
     - Partial Refund 2 ($80.00): Fee Reversal = $2.32. Merchant Debited: $77.68.
     - Total Fee Reversal = $3.48. Retained Fee = $0.30. Net Merchant Position for P2 = $-\$0.30$ (-30 cents).
   - **Cumulative Merchant Account State (`MerchantBalanceService`)**:
     - `availableBalanceCents`: $2 \times (-\$0.30) = -\$0.60$ (-60 cents)
     - `totalVolumeCents`: $\$150.00 + \$120.00 = \$270.00$ ($27,000\text{ cents}$)
     - `totalRefundsCents`: $\$150.00 + \$40.00 + \$80.00 = \$270.00$ ($27,000\text{ cents}$)
     - `totalFeesCents`: $\$0.30 + \$0.30 = \$0.60$ ($60\text{ cents}$ retained)

---

## 4. Real-Time Balance Projections

Rather than storing mutable scalar balance fields that are vulnerable to lost updates and race conditions, account balances are **projected in real time** using aggregate queries over immutable ledger entries.

### 4.1. Account Balance Calculation Formula

Depending on account type, the balance is projected as follows:

$$\text{Balance}(\text{ASSET}, \text{EXPENSE}) = \sum \text{DebitAmount} - \sum \text{CreditAmount}$$

$$\text{Balance}(\text{LIABILITY}, \text{REVENUE}, \text{EQUITY}) = \sum \text{CreditAmount} - \sum \text{DebitAmount}$$

### 4.2. Optimized SQL Projection Query
[`com.lky.kaipay.ledger.repository.LedgerEntryRepository`](../backend/src/main/java/com/lky/kaipay/ledger/repository/LedgerEntryRepository.java)

```sql
SELECT 
    COALESCE(SUM(
        CASE 
            WHEN a.account_type IN ('LIABILITY', 'REVENUE', 'EQUITY') THEN
                CASE WHEN le.entry_type = 'CREDIT' THEN le.amount_cents ELSE -le.amount_cents END
            ELSE
                CASE WHEN le.entry_type = 'DEBIT' THEN le.amount_cents ELSE -le.amount_cents END
        END
    ), 0)
FROM ledger_entries le
JOIN accounts a ON le.account_id = a.id
WHERE le.account_id = :accountId;
```

---

## 5. Concurrency Controls & Pessimistic Row Locking

To prevent race conditions during concurrent refund requests on the same payment aggregate (e.g. two concurrent refund requests of $60.00 on a $100.00 payment, which would total $120.00 and cause an over-refund), KaiPay enforces **Pessimistic Write Locking** at the database layer:

### Implementation in `RefundService`
[`com.lky.kaipay.refund.service.RefundService`](../backend/src/main/java/com/lky/kaipay/refund/service/RefundService.java)

```java
@Transactional
public RefundResponse createRefund(UUID merchantId, UUID paymentId, String idempotencyKey, CreateRefundRequest request) {
    // 1. Acquire exclusive row-level lock on the target Payment record
    Payment payment = paymentRepository.findByIdAndMerchantIdForUpdate(paymentId, merchantId)
            .orElseThrow(() -> new EntityNotFoundException("Payment not found"));

    // 2. Validate state (Must be CAPTURED or PARTIALLY_REFUNDED)
    if (payment.getStatus() != PaymentStatus.CAPTURED && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
        throw new InvalidRefundException("Cannot refund payment in status " + payment.getStatus());
    }

    // 3. Compute existing total refunded amount
    Long sum = refundRepository.sumRefundedAmountByPaymentId(paymentId);
    long totalRefunded = sum != null ? sum : 0L;
    long maxRefundable = payment.getAmountCents() - totalRefunded;

    // 4. Invariant check: Requested refund cannot exceed remaining refundable balance
    if (request.getAmountCents() > maxRefundable) {
        throw new InvalidRefundException("Refund amount exceeds refundable balance of " + maxRefundable + " cents");
    }

    // 5. Persist Refund + Update Payment Status + Post Reversing Journal
    ...
}
```

The underlying native SQL executes:
```sql
SELECT * FROM payments 
WHERE id = :paymentId AND merchant_id = :merchantId 
FOR UPDATE;
```
This forces concurrent refund transactions to block and serialize, ensuring the second transaction reads the updated `totalRefunded` amount and rejects invalid over-refunds.
