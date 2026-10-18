-- Durable simulated provider evidence; retained for the associated payment lifetime.
CREATE TABLE simulated_authorization_operations (
    payment_id UUID PRIMARY KEY REFERENCES payments(id) ON DELETE RESTRICT,
    namespace VARCHAR(40) NOT NULL CHECK (namespace = 'kaipay-sim-auth-v1'),
    contract_version INTEGER NOT NULL CHECK (contract_version = 1),
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    disposition VARCHAR(30) NOT NULL CHECK (disposition IN ('UNRESOLVED','APPROVED','DECLINED','REJECTED_NO_ACCEPTANCE')),
    evaluation_count BIGINT NOT NULL DEFAULT 0 CHECK (evaluation_count >= 0),
    authorization_code VARCHAR(100),
    decline_code VARCHAR(50),
    decline_message TEXT,
    failure_code VARCHAR(50),
    failure_message TEXT,
    result_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_simulated_authorization_result CHECK (
        (disposition = 'UNRESOLVED' AND result_at IS NULL AND authorization_code IS NULL
            AND decline_code IS NULL AND decline_message IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (disposition = 'APPROVED' AND result_at IS NOT NULL AND evaluation_count > 0
            AND authorization_code IS NOT NULL AND length(authorization_code) > 0
            AND decline_code IS NULL AND decline_message IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (disposition = 'DECLINED' AND result_at IS NOT NULL AND evaluation_count > 0
            AND authorization_code IS NULL AND decline_code IS NOT NULL AND length(decline_code) > 0
            AND decline_message IS NOT NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (disposition = 'REJECTED_NO_ACCEPTANCE' AND result_at IS NOT NULL AND evaluation_count > 0
            AND authorization_code IS NULL AND decline_code IS NULL AND decline_message IS NULL
            AND failure_code IS NOT NULL AND failure_code = 'SIMULATED_NO_ACCEPTANCE' AND failure_message IS NOT NULL)
    )
);
