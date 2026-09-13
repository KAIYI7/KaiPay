-- =============================================================================
-- KaiPay V5: Double-Entry Ledger, Financial Journals & Refunds Schema
-- =============================================================================

-- 1. Accounts Table (Chart of Accounts)
CREATE TABLE accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID REFERENCES merchants(id), -- NULL for platform/clearing accounts
    account_number VARCHAR(64) NOT NULL UNIQUE,
    account_name VARCHAR(100) NOT NULL,
    account_type VARCHAR(30) NOT NULL, -- 'ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_merchant ON accounts (merchant_id);

-- 2. Financial Journals Table (Atomic Transaction Container)
CREATE TABLE journals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_number VARCHAR(64) NOT NULL UNIQUE,
    source_type VARCHAR(50) NOT NULL, -- 'PAYMENT_CAPTURE', 'PAYMENT_REFUND', 'SETTLEMENT'
    source_id VARCHAR(100) NOT NULL,  -- payment_id or refund_id
    event_id UUID,                     -- Idempotency key from domain event
    merchant_id UUID REFERENCES merchants(id),
    description TEXT NOT NULL,
    posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_journals_source_event UNIQUE (source_type, source_id, event_id)
);

CREATE INDEX idx_journals_merchant ON journals (merchant_id);
CREATE INDEX idx_journals_source ON journals (source_type, source_id);

-- 3. Ledger Entries Table (Immutable Debits and Credits)
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_id UUID NOT NULL REFERENCES journals(id) ON DELETE RESTRICT,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    entry_type VARCHAR(10) NOT NULL, -- 'DEBIT', 'CREDIT'
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ledger_entries_journal ON ledger_entries (journal_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id);

-- 4. Refunds Table
CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES payments(id),
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(30) NOT NULL DEFAULT 'COMPLETED', -- 'PENDING', 'COMPLETED', 'FAILED'
    reason VARCHAR(255),
    idempotency_key VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_refunds_idempotency UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_refunds_payment ON refunds (payment_id);
CREATE INDEX idx_refunds_merchant ON refunds (merchant_id);
