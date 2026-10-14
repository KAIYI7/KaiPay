-- Provisioned identities are independent of the legacy synthetic merchant API-key fields.
-- No users or credentials are seeded by migrations or application startup.
CREATE TABLE demo_users (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('MERCHANT', 'ADMIN')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    credential_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE demo_merchant_memberships (
    user_id UUID NOT NULL REFERENCES demo_users(id) ON DELETE CASCADE,
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, merchant_id)
);
