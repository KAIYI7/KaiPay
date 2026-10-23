-- Ephemeral internal structural audit. This is not provider reconciliation or settlement.
-- Exactly three bind parameters: merchant UUID, inclusive root time, exclusive root time.
-- Linked context may predate/follow the root interval: this is one current MVCC snapshot.
-- The caller must use a read-only transaction, statement timeout and output-size bound.
WITH
params(merchant_id, from_inclusive, cutoff_exclusive) AS (
    SELECT ?::uuid, ?::timestamptz, ?::timestamptz
),
seed_payments AS MATERIALIZED (
    SELECT p.id, p.merchant_id, p.amount_cents, p.currency, p.status
    FROM payments p CROSS JOIN params x
    WHERE p.merchant_id = x.merchant_id
      AND p.created_at >= x.from_inclusive AND p.created_at < x.cutoff_exclusive
    LIMIT 1001
),
seed_refunds AS MATERIALIZED (
    SELECT r.id, r.merchant_id, r.payment_id, r.amount_cents, r.currency, r.status
    FROM refunds r CROSS JOIN params x
    WHERE r.merchant_id = x.merchant_id
      AND r.created_at >= x.from_inclusive AND r.created_at < x.cutoff_exclusive
    LIMIT 1001
),
seed_journals AS MATERIALIZED (
    SELECT j.id, j.merchant_id, j.source_type, j.source_id
    FROM journals j CROSS JOIN params x
    WHERE j.merchant_id = x.merchant_id
      AND j.posted_at >= x.from_inclusive AND j.posted_at < x.cutoff_exclusive
    LIMIT 1001
),
seed_limits AS MATERIALIZED (
    SELECT (SELECT count(*) > 1000 FROM seed_payments)
        OR (SELECT count(*) > 1000 FROM seed_refunds)
        OR (SELECT count(*) > 1000 FROM seed_journals) AS exceeded
),
-- Nonrecursive closure: root payments, parents of root refunds, and sources/parents
-- of root journals. Other refunds of these payments are included in the next CTE.
scoped_payments AS MATERIALIZED (
    SELECT p.id, p.merchant_id, p.amount_cents, p.currency, p.status
    FROM payments p CROSS JOIN params x
    WHERE NOT (SELECT exceeded FROM seed_limits)
      AND p.merchant_id = x.merchant_id
      AND (EXISTS (SELECT 1 FROM seed_payments s WHERE s.id = p.id)
        OR EXISTS (SELECT 1 FROM seed_refunds s WHERE s.payment_id = p.id)
        OR EXISTS (SELECT 1 FROM seed_journals s
                   WHERE s.source_type = 'PAYMENT_CAPTURE' AND s.source_id = p.id::text)
        OR EXISTS (SELECT 1 FROM seed_journals s JOIN refunds r
                       ON s.source_type = 'PAYMENT_REFUND' AND s.source_id = r.id::text
                   WHERE r.merchant_id = x.merchant_id AND r.payment_id = p.id))
    LIMIT 1001
),
payment_limits AS MATERIALIZED (
    SELECT exceeded OR (SELECT count(*) > 1000 FROM scoped_payments) AS exceeded
    FROM seed_limits
),
scoped_refunds AS MATERIALIZED (
    SELECT r.id, r.merchant_id, r.payment_id, r.amount_cents, r.currency, r.status
    FROM refunds r CROSS JOIN params x
    WHERE NOT (SELECT exceeded FROM payment_limits)
      AND r.merchant_id = x.merchant_id
      AND (EXISTS (SELECT 1 FROM seed_refunds s WHERE s.id = r.id)
        OR EXISTS (SELECT 1 FROM scoped_payments p WHERE p.id = r.payment_id)
        OR EXISTS (SELECT 1 FROM seed_journals s
                   WHERE s.source_type = 'PAYMENT_REFUND' AND s.source_id = r.id::text))
    LIMIT 1001
),
refund_limits AS MATERIALIZED (
    SELECT exceeded OR (SELECT count(*) > 1000 FROM scoped_refunds) AS exceeded
    FROM payment_limits
),
scoped_journals AS MATERIALIZED (
    SELECT j.id, j.merchant_id, j.source_type, j.source_id
    FROM journals j CROSS JOIN params x
    WHERE NOT (SELECT exceeded FROM refund_limits)
      AND j.merchant_id = x.merchant_id
      AND (EXISTS (SELECT 1 FROM seed_journals s WHERE s.id = j.id)
        OR EXISTS (SELECT 1 FROM scoped_payments p
                   WHERE j.source_type = 'PAYMENT_CAPTURE' AND j.source_id = p.id::text)
        OR EXISTS (SELECT 1 FROM scoped_refunds r
                   WHERE j.source_type = 'PAYMENT_REFUND' AND j.source_id = r.id::text))
    LIMIT 1001
),
journal_limits AS MATERIALIZED (
    SELECT exceeded OR (SELECT count(*) > 1000 FROM scoped_journals) AS exceeded
    FROM refund_limits
),
scoped_entries AS MATERIALIZED (
    SELECT e.journal_id, e.account_id, e.entry_type, e.amount_cents, e.currency
    FROM ledger_entries e
    WHERE NOT (SELECT exceeded FROM journal_limits)
      AND EXISTS (SELECT 1 FROM scoped_journals j WHERE j.id = e.journal_id)
    LIMIT 10001
),
limits AS MATERIALIZED (
    SELECT exceeded OR (SELECT count(*) > 10000 FROM scoped_entries) AS exceeded
    FROM journal_limits
),
-- Only owned rows reach observations. Foreign associations are Boolean evidence,
-- never another tenant's identifiers, statuses, amounts, currencies or counts.
payment_facts AS MATERIALIZED (
    SELECT p.*,
        (SELECT count(*) FROM scoped_journals j
         WHERE j.source_type = 'PAYMENT_CAPTURE' AND j.source_id = p.id::text) AS journal_count,
        EXISTS (SELECT 1 FROM journals j WHERE j.source_type = 'PAYMENT_CAPTURE'
                AND j.source_id = p.id::text AND j.merchant_id IS DISTINCT FROM p.merchant_id)
            AS foreign_journal,
        EXISTS (SELECT 1 FROM refunds r WHERE r.payment_id = p.id
                AND r.merchant_id IS DISTINCT FROM p.merchant_id) AS foreign_refund,
        EXISTS (SELECT 1 FROM scoped_refunds r WHERE r.payment_id = p.id
                AND r.currency IS DISTINCT FROM p.currency) AS mixed_refund_currency,
        (SELECT count(*) FROM scoped_refunds r
         WHERE r.payment_id = p.id AND r.status = 'COMPLETED') AS completed_refund_count,
        coalesce((SELECT sum(r.amount_cents::numeric) FROM scoped_refunds r
                  WHERE r.payment_id = p.id AND r.status = 'COMPLETED'
                    AND r.currency = p.currency), 0::numeric) AS completed_refund_amount
    FROM scoped_payments p WHERE NOT (SELECT exceeded FROM limits)
),
payment_reasons AS (
    SELECT p.*, array_remove(ARRAY[
        CASE WHEN foreign_journal THEN 'JOURNAL_OWNER_MISMATCH' END,
        CASE WHEN foreign_refund THEN 'REFUND_OWNER_MISMATCH' END,
        CASE WHEN mixed_refund_currency THEN 'REFUND_CURRENCY_MISMATCH' END,
        CASE WHEN currency !~ '^[A-Z]{3}$' THEN 'INVALID_SOURCE_CURRENCY' END,
        CASE WHEN amount_cents <= 0 THEN 'INVALID_SOURCE_AMOUNT' END,
        CASE WHEN status IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED','REFUND_PENDING')
                   AND journal_count = 0 AND NOT foreign_journal THEN 'MISSING_CAPTURE_JOURNAL' END,
        CASE WHEN journal_count > 1 THEN 'DUPLICATE_CAPTURE_JOURNAL' END,
        CASE WHEN status IN ('CREATED','PROCESSING','AUTHORIZED','FAILED','DECLINED')
                   AND journal_count > 0 THEN 'UNEXPECTED_CAPTURE_JOURNAL' END,
        CASE WHEN NOT foreign_refund AND NOT mixed_refund_currency
                   AND completed_refund_amount > amount_cents::numeric THEN 'REFUND_TOTAL_EXCEEDS_PAYMENT' END,
        CASE WHEN NOT foreign_refund AND NOT mixed_refund_currency AND
                   ((status = 'CAPTURED' AND completed_refund_amount <> 0)
                    OR (status = 'PARTIALLY_REFUNDED'
                        AND NOT (completed_refund_amount > 0 AND completed_refund_amount < amount_cents::numeric))
                    OR (status = 'REFUNDED' AND completed_refund_amount <> amount_cents::numeric)
                    OR (status IN ('CREATED','PROCESSING','AUTHORIZED','FAILED','DECLINED')
                        AND completed_refund_count > 0)) THEN 'PAYMENT_REFUND_STATUS_MISMATCH' END
    ]::text[], NULL) AS failures
    FROM payment_facts p
),
refund_facts AS MATERIALIZED (
    SELECT r.*, p.id IS NOT NULL AS parent_exists,
        p.merchant_id IS DISTINCT FROM r.merchant_id AS foreign_parent,
        CASE WHEN p.merchant_id = r.merchant_id THEN p.currency END AS parent_currency,
        CASE WHEN p.merchant_id = r.merchant_id THEN p.status END AS parent_status,
        (SELECT count(*) FROM scoped_journals j
         WHERE j.source_type = 'PAYMENT_REFUND' AND j.source_id = r.id::text) AS journal_count,
        EXISTS (SELECT 1 FROM journals j WHERE j.source_type = 'PAYMENT_REFUND'
                AND j.source_id = r.id::text AND j.merchant_id IS DISTINCT FROM r.merchant_id)
            AS foreign_journal
    FROM scoped_refunds r LEFT JOIN payments p ON p.id = r.payment_id
    WHERE NOT (SELECT exceeded FROM limits)
),
refund_reasons AS (
    SELECT r.*, array_remove(ARRAY[
        CASE WHEN NOT parent_exists THEN 'MISSING_PAYMENT' END,
        CASE WHEN parent_exists AND foreign_parent THEN 'PAYMENT_OWNER_MISMATCH' END,
        CASE WHEN parent_exists AND NOT foreign_parent
                   AND currency IS DISTINCT FROM parent_currency THEN 'REFUND_CURRENCY_MISMATCH' END,
        CASE WHEN foreign_journal THEN 'JOURNAL_OWNER_MISMATCH' END,
        CASE WHEN currency !~ '^[A-Z]{3}$' THEN 'INVALID_SOURCE_CURRENCY' END,
        CASE WHEN amount_cents <= 0 THEN 'INVALID_SOURCE_AMOUNT' END,
        CASE WHEN status = 'COMPLETED' AND journal_count = 0 AND NOT foreign_journal
                   THEN 'MISSING_REFUND_JOURNAL' END,
        CASE WHEN journal_count > 1 THEN 'DUPLICATE_REFUND_JOURNAL' END,
        CASE WHEN status IN ('PENDING','FAILED') AND journal_count > 0 THEN 'UNEXPECTED_REFUND_JOURNAL' END,
        CASE WHEN status = 'COMPLETED' AND parent_exists AND NOT foreign_parent
                   AND parent_status IN ('CREATED','PROCESSING','AUTHORIZED','FAILED','DECLINED')
                   THEN 'PAYMENT_REFUND_STATUS_MISMATCH' END
    ]::text[], NULL) AS failures
    FROM refund_facts r
),
-- Existing financial writers use UUID.toString(): the exact lowercase canonical
-- UUID text is the source identity. No normalization/reinterpretation is applied.
-- Text comparison deliberately tolerates malformed retained source identifiers.
journal_sources AS MATERIALIZED (
    SELECT j.*,
        j.source_type IN ('PAYMENT_CAPTURE','PAYMENT_REFUND') AS supported_source,
        CASE WHEN j.source_type = 'PAYMENT_CAPTURE' THEN p.id IS NOT NULL
             WHEN j.source_type = 'PAYMENT_REFUND' THEN r.id IS NOT NULL ELSE false END AS source_exists,
        CASE WHEN j.source_type = 'PAYMENT_CAPTURE' THEN p.merchant_id IS DISTINCT FROM j.merchant_id
             WHEN j.source_type = 'PAYMENT_REFUND' THEN r.merchant_id IS DISTINCT FROM j.merchant_id
             ELSE false END AS foreign_source,
        j.source_type = 'PAYMENT_REFUND' AND r.id IS NOT NULL AND rp.id IS NULL AS missing_parent,
        j.source_type = 'PAYMENT_REFUND' AND rp.id IS NOT NULL
            AND rp.merchant_id IS DISTINCT FROM j.merchant_id AS foreign_parent,
        CASE WHEN j.source_type = 'PAYMENT_CAPTURE' AND p.merchant_id = j.merchant_id THEN p.amount_cents
             WHEN j.source_type = 'PAYMENT_REFUND' AND r.merchant_id = j.merchant_id THEN r.amount_cents END AS source_amount,
        CASE WHEN j.source_type = 'PAYMENT_CAPTURE' AND p.merchant_id = j.merchant_id THEN p.currency
             WHEN j.source_type = 'PAYMENT_REFUND' AND r.merchant_id = j.merchant_id THEN r.currency END AS source_currency,
        CASE WHEN j.source_type = 'PAYMENT_CAPTURE' AND p.merchant_id = j.merchant_id THEN p.status
             WHEN j.source_type = 'PAYMENT_REFUND' AND r.merchant_id = j.merchant_id THEN r.status END AS source_status,
        CASE WHEN r.merchant_id = j.merchant_id AND rp.merchant_id = j.merchant_id
             THEN r.currency IS DISTINCT FROM rp.currency ELSE false END AS refund_currency_mismatch
    FROM scoped_journals j
    LEFT JOIN payments p ON j.source_type = 'PAYMENT_CAPTURE' AND j.source_id = p.id::text
    LEFT JOIN refunds r ON j.source_type = 'PAYMENT_REFUND' AND j.source_id = r.id::text
    LEFT JOIN payments rp ON rp.id = r.payment_id
    WHERE NOT (SELECT exceeded FROM limits)
),
entry_facts AS MATERIALIZED (
    SELECT e.*, a.id IS NULL AS missing_account,
        a.merchant_id IS NOT NULL AND a.merchant_id IS DISTINCT FROM j.merchant_id AS foreign_account,
        e.currency IS DISTINCT FROM a.currency AS account_currency_mismatch,
        j.source_currency IS NOT NULL AND e.currency IS DISTINCT FROM j.source_currency AS source_currency_mismatch,
        a.merchant_id IS NULL AND a.account_type = 'ASSET'
            AND a.account_number = '1000-CUSTOMER-RECEIVABLE' AS is_receivable,
        ((a.merchant_id = j.merchant_id AND a.account_type = 'LIABILITY')
            OR (a.merchant_id IS NULL AND a.account_type = 'REVENUE'
                AND a.account_number = '4000-PLATFORM-FEE-REVENUE')) AS is_other_side,
        j.source_type
    FROM scoped_entries e JOIN journal_sources j ON j.id = e.journal_id
    LEFT JOIN accounts a ON a.id = e.account_id
),
currency_balances AS (
    SELECT journal_id, currency,
        coalesce(sum(amount_cents::numeric) FILTER (WHERE entry_type = 'DEBIT'), 0::numeric) AS debit,
        coalesce(sum(amount_cents::numeric) FILTER (WHERE entry_type = 'CREDIT'), 0::numeric) AS credit
    FROM entry_facts GROUP BY journal_id, currency
),
journal_facts AS (
    SELECT j.*,
        (SELECT count(*) FROM entry_facts e WHERE e.journal_id = j.id) AS entry_count,
        EXISTS (SELECT 1 FROM currency_balances b WHERE b.journal_id = j.id AND b.debit <> b.credit) AS unbalanced,
        EXISTS (SELECT 1 FROM entry_facts e WHERE e.journal_id = j.id AND e.missing_account) AS missing_account,
        EXISTS (SELECT 1 FROM entry_facts e WHERE e.journal_id = j.id AND e.foreign_account) AS foreign_account,
        EXISTS (SELECT 1 FROM entry_facts e WHERE e.journal_id = j.id
                AND e.entry_type NOT IN ('DEBIT','CREDIT')) AS invalid_entry_type,
        EXISTS (SELECT 1 FROM entry_facts e WHERE e.journal_id = j.id AND e.amount_cents <= 0) AS invalid_entry_amount,
        EXISTS (SELECT 1 FROM entry_facts e WHERE e.journal_id = j.id
                AND e.currency !~ '^[A-Z]{3}$') AS invalid_entry_currency,
        EXISTS (SELECT 1 FROM entry_facts e WHERE e.journal_id = j.id
                AND e.account_currency_mismatch) AS account_currency_mismatch,
        EXISTS (SELECT 1 FROM entry_facts e WHERE e.journal_id = j.id
                AND e.source_currency_mismatch) AS source_currency_mismatch,
        EXISTS (SELECT 1 FROM entry_facts e WHERE e.journal_id = j.id AND j.supported_source
                AND NOT coalesce((e.is_receivable AND e.entry_type =
                        CASE WHEN j.source_type = 'PAYMENT_CAPTURE' THEN 'DEBIT' ELSE 'CREDIT' END)
                    OR (e.is_other_side AND e.entry_type =
                        CASE WHEN j.source_type = 'PAYMENT_CAPTURE' THEN 'CREDIT' ELSE 'DEBIT' END), false))
            AS invalid_account_role,
        coalesce((SELECT sum(e.amount_cents::numeric) FROM entry_facts e WHERE e.journal_id = j.id
                  AND e.is_receivable AND e.entry_type =
                      CASE WHEN j.source_type = 'PAYMENT_CAPTURE' THEN 'DEBIT' ELSE 'CREDIT' END
                  AND e.currency = j.source_currency), 0::numeric) AS receivable_amount
    FROM journal_sources j
),
journal_reasons AS (
    SELECT j.*, array_remove(ARRAY[
        CASE WHEN supported_source AND NOT source_exists THEN 'MISSING_SOURCE' END,
        CASE WHEN supported_source AND source_exists AND foreign_source THEN 'SOURCE_OWNER_MISMATCH' END,
        CASE WHEN missing_parent THEN 'MISSING_PAYMENT' END,
        CASE WHEN foreign_parent THEN 'PAYMENT_OWNER_MISMATCH' END,
        CASE WHEN refund_currency_mismatch THEN 'REFUND_CURRENCY_MISMATCH' END,
        CASE WHEN source_currency !~ '^[A-Z]{3}$' THEN 'INVALID_SOURCE_CURRENCY' END,
        CASE WHEN source_amount <= 0 THEN 'INVALID_SOURCE_AMOUNT' END,
        CASE WHEN entry_count = 0 THEN 'EMPTY_JOURNAL' END,
        CASE WHEN unbalanced THEN 'UNBALANCED_JOURNAL' END,
        CASE WHEN missing_account THEN 'MISSING_ACCOUNT' END,
        CASE WHEN foreign_account THEN 'ACCOUNT_OWNER_MISMATCH' END,
        CASE WHEN invalid_entry_type THEN 'INVALID_ENTRY_TYPE' END,
        CASE WHEN invalid_entry_amount THEN 'INVALID_ENTRY_AMOUNT' END,
        CASE WHEN invalid_entry_currency THEN 'INVALID_ENTRY_CURRENCY' END,
        CASE WHEN account_currency_mismatch THEN 'ACCOUNT_CURRENCY_MISMATCH' END,
        CASE WHEN source_currency_mismatch THEN 'SOURCE_CURRENCY_MISMATCH' END,
        CASE WHEN invalid_account_role THEN 'INVALID_ACCOUNT_ROLE' END,
        CASE WHEN supported_source AND source_exists AND NOT foreign_source AND source_amount IS NOT NULL
                   AND receivable_amount <> source_amount::numeric THEN 'SOURCE_GROSS_MISMATCH' END,
        CASE WHEN source_type = 'PAYMENT_CAPTURE'
                   AND source_status IN ('CREATED','PROCESSING','AUTHORIZED','FAILED','DECLINED')
                   THEN 'UNEXPECTED_CAPTURE_JOURNAL' END,
        CASE WHEN source_type = 'PAYMENT_REFUND' AND source_status IN ('PENDING','FAILED')
                   THEN 'UNEXPECTED_REFUND_JOURNAL' END
    ]::text[], NULL) AS failures
    FROM journal_facts j
),
observations AS (
    SELECT 'PAYMENT'::text AS kind, id,
        jsonb_build_object('kind','PAYMENT','id',id,
            'classification',CASE WHEN cardinality(failures) > 0 THEN 'INCONSISTENT'
                WHEN status = 'REFUND_PENDING' THEN 'NOT_ASSESSED'
                WHEN status IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED') THEN 'CONSISTENT'
                WHEN status IN ('CREATED','PROCESSING','AUTHORIZED','FAILED','DECLINED') THEN 'NOT_APPLICABLE'
                ELSE 'NOT_ASSESSED' END,
            'reasonCodes',to_jsonb(failures || CASE
                WHEN status = 'REFUND_PENDING' THEN ARRAY['PAYMENT_REFUND_PENDING']
                WHEN status NOT IN ('CREATED','PROCESSING','AUTHORIZED','CAPTURED','FAILED','DECLINED',
                                    'REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED') THEN ARRAY['UNSUPPORTED_PAYMENT_STATUS']
                ELSE ARRAY[]::text[] END),
            'evidence',jsonb_build_object(
                'amountMinor',amount_cents::text,
                'currency',CASE WHEN currency ~ '^[A-Z]{3}$' THEN currency ELSE 'OTHER' END,
                'status',CASE WHEN status IN ('CREATED','PROCESSING','AUTHORIZED','CAPTURED','FAILED','DECLINED',
                    'REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED') THEN status ELSE 'OTHER' END,
                'captureJournalCount',journal_count,'foreignJournalAssociation',foreign_journal,
                'foreignRefundAssociation',foreign_refund,'mixedRefundCurrency',mixed_refund_currency,
                'completedRefundCount',completed_refund_count,
                'completedRefundAmountMinor',CASE WHEN NOT foreign_refund AND NOT mixed_refund_currency
                    THEN completed_refund_amount::text ELSE NULL END)) AS observation
    FROM payment_reasons
    UNION ALL
    SELECT 'REFUND', id,
        jsonb_build_object('kind','REFUND','id',id,
            'classification',CASE WHEN cardinality(failures) > 0 THEN 'INCONSISTENT'
                WHEN status = 'COMPLETED' AND parent_status IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED') THEN 'CONSISTENT'
                ELSE 'NOT_ASSESSED' END,
            'reasonCodes',to_jsonb(failures || CASE
                WHEN status IN ('PENDING','FAILED') THEN ARRAY['REFUND_NOT_COMPLETED']
                WHEN status NOT IN ('PENDING','FAILED','COMPLETED') THEN ARRAY['UNSUPPORTED_REFUND_STATUS']
                WHEN parent_status = 'REFUND_PENDING' THEN ARRAY['PAYMENT_REFUND_PENDING']
                WHEN parent_exists AND NOT foreign_parent AND parent_status NOT IN
                    ('CREATED','PROCESSING','AUTHORIZED','CAPTURED','FAILED','DECLINED',
                     'REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED') THEN ARRAY['UNSUPPORTED_PAYMENT_STATUS']
                ELSE ARRAY[]::text[] END),
            'evidence',jsonb_build_object(
                'amountMinor',amount_cents::text,
                'currency',CASE WHEN currency ~ '^[A-Z]{3}$' THEN currency ELSE 'OTHER' END,
                'status',CASE WHEN status IN ('PENDING','COMPLETED','FAILED') THEN status ELSE 'OTHER' END,
                'refundJournalCount',journal_count,'parentExists',parent_exists,
                'foreignParentAssociation',parent_exists AND foreign_parent,
                'foreignJournalAssociation',foreign_journal))
    FROM refund_reasons
    UNION ALL
    SELECT 'JOURNAL', id,
        jsonb_build_object('kind','JOURNAL','id',id,
            'classification',CASE WHEN cardinality(failures) > 0 THEN 'INCONSISTENT'
                WHEN NOT supported_source THEN 'NOT_ASSESSED'
                WHEN source_type = 'PAYMENT_CAPTURE' AND source_status = 'REFUND_PENDING' THEN 'NOT_ASSESSED'
                WHEN source_type = 'PAYMENT_CAPTURE' AND source_status NOT IN
                    ('CREATED','PROCESSING','AUTHORIZED','CAPTURED','FAILED','DECLINED',
                     'REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED') THEN 'NOT_ASSESSED'
                WHEN source_type = 'PAYMENT_REFUND' AND source_status NOT IN ('PENDING','COMPLETED','FAILED')
                    THEN 'NOT_ASSESSED' ELSE 'CONSISTENT' END,
            'reasonCodes',to_jsonb(failures || CASE
                WHEN NOT supported_source THEN ARRAY['UNSUPPORTED_SOURCE_TYPE']
                WHEN source_type = 'PAYMENT_CAPTURE' AND source_status = 'REFUND_PENDING'
                    THEN ARRAY['PAYMENT_REFUND_PENDING']
                WHEN source_type = 'PAYMENT_CAPTURE' AND source_status NOT IN
                    ('CREATED','PROCESSING','AUTHORIZED','CAPTURED','FAILED','DECLINED',
                     'REFUND_PENDING','PARTIALLY_REFUNDED','REFUNDED') THEN ARRAY['UNSUPPORTED_PAYMENT_STATUS']
                WHEN source_type = 'PAYMENT_REFUND' AND source_status NOT IN ('PENDING','COMPLETED','FAILED')
                    THEN ARRAY['UNSUPPORTED_REFUND_STATUS'] ELSE ARRAY[]::text[] END),
            'evidence',jsonb_build_object(
                'sourceType',CASE WHEN supported_source THEN source_type ELSE 'OTHER' END,
                'sourceExists',source_exists,'foreignSourceAssociation',source_exists AND foreign_source,
                'foreignParentAssociation',foreign_parent,'entryCount',entry_count,
                'foreignAccountAssociation',foreign_account,
                'currency',CASE WHEN source_currency ~ '^[A-Z]{3}$' THEN source_currency ELSE 'OTHER' END,
                'sourceAmountMinor',source_amount::text,
                'receivableAmountMinor',CASE WHEN supported_source AND source_exists AND NOT foreign_source
                    THEN receivable_amount::text ELSE NULL END))
    FROM journal_reasons
)
SELECT jsonb_build_object(
    'scope','INTERNAL_ACCOUNTING','ruleset','INTERNAL_ACCOUNTING_V1.0','status','COMPLETE',
    'merchantId',x.merchant_id,'fromInclusive',x.from_inclusive,'cutoffExclusive',x.cutoff_exclusive,
    'snapshotObservedAt',statement_timestamp(),
    'merchantExists',EXISTS (SELECT 1 FROM merchants m WHERE m.id = x.merchant_id),
    'cutoffValid',x.from_inclusive < x.cutoff_exclusive AND x.cutoff_exclusive <= statement_timestamp(),
    'limitExceeded',l.exceeded,
    'coverage',jsonb_build_object('paymentCount',(SELECT count(*) FROM scoped_payments),
        'refundCount',(SELECT count(*) FROM scoped_refunds),'journalCount',(SELECT count(*) FROM scoped_journals),
        'entryCount',(SELECT count(*) FROM scoped_entries)),
    'observations',CASE WHEN l.exceeded THEN '[]'::jsonb ELSE coalesce(
        (SELECT jsonb_agg(observation ORDER BY kind,id) FROM observations),'[]'::jsonb) END
)::text AS report_json
FROM params x CROSS JOIN limits l
