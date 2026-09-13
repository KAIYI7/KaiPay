-- =============================================================================
-- KaiPay V1: Core Relational Schema Initialization
-- Bounded Contexts: Merchant, Customer, PaymentMethod, Payment, Idempotency
-- =============================================================================

-- 1. Merchants Table
CREATE TABLE merchants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    api_key_hash VARCHAR(64) NOT NULL,
    webhook_url VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_merchants_api_key_hash UNIQUE (api_key_hash)
);

-- 2. Customers Table (Scoped to a Merchant)
CREATE TABLE customers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    email VARCHAR(150) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_customers_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE RESTRICT,
    CONSTRAINT uk_merchant_customer_email UNIQUE (merchant_id, email)
);

CREATE INDEX idx_customers_merchant ON customers(merchant_id);

-- 3. Payment Methods Table (Tokenized Vault - PCI-DSS Simulation)
CREATE TABLE payment_methods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID NOT NULL,
    type VARCHAR(30) NOT NULL, -- e.g., CARD, BANK_ACCOUNT
    token VARCHAR(100) NOT NULL,
    masked_number VARCHAR(20) NOT NULL, -- e.g., **** **** **** 4242
    expiry_month INT NOT NULL CHECK (expiry_month BETWEEN 1 AND 12),
    expiry_year INT NOT NULL CHECK (expiry_year >= 2024),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, EXPIRED, REVOKED
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payment_methods_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT,
    CONSTRAINT uk_payment_methods_token UNIQUE (token)
);

CREATE INDEX idx_payment_methods_customer ON payment_methods(customer_id);

-- 4. Payments Table (Aggregate Root)
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    payment_method_id UUID,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL, -- ISO 4217 (USD, EUR, TWD, GBP)
    status VARCHAR(30) NOT NULL,  -- CREATED, PROCESSING, AUTHORIZED, CAPTURED, FAILED, REFUNDED
    idempotency_key VARCHAR(100) NOT NULL,
    gateway_reference VARCHAR(100),
    failure_code VARCHAR(50),
    failure_message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0, -- Optimistic Locking
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payments_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payments_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payments_method FOREIGN KEY (payment_method_id) REFERENCES payment_methods(id) ON DELETE RESTRICT,
    CONSTRAINT uk_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_payments_merchant_created ON payments(merchant_id, created_at DESC);
CREATE INDEX idx_payments_status ON payments(status);

-- 5. API Idempotency Records Table
CREATE TABLE idempotency_records (
    merchant_id UUID NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_idempotency_records PRIMARY KEY (merchant_id, idempotency_key),
    CONSTRAINT fk_idempotency_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id) ON DELETE CASCADE
);

CREATE INDEX idx_idempotency_expires_at ON idempotency_records(expires_at);
