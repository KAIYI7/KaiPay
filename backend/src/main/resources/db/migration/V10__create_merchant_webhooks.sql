-- Forward-only merchant notifications. Existing merchant URLs and money rows are untouched.
CREATE TABLE merchant_webhook_endpoints (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    url VARCHAR(512) NOT NULL,
    hostname VARCHAR(253) NOT NULL,
    path VARCHAR(255) NOT NULL,
    pins TEXT[] NOT NULL CHECK (cardinality(pins) BETWEEN 1 AND 4),
    key_id UUID NOT NULL UNIQUE,
    secret_ref VARCHAR(100) NOT NULL UNIQUE CHECK (secret_ref ~ '^[A-Za-z0-9_-]{1,100}$'),
    state VARCHAR(10) NOT NULL CHECK (state IN ('ACTIVE', 'RETIRED')),
    created_by VARCHAR(100) NOT NULL CHECK (length(created_by) BETWEEN 1 AND 100),
    reason_code VARCHAR(40) NOT NULL CHECK (reason_code ~ '^[A-Z_]{1,40}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    retired_at TIMESTAMPTZ,
    retired_by VARCHAR(100) CHECK (retired_by IS NULL OR length(retired_by) BETWEEN 1 AND 100),
    retirement_reason_code VARCHAR(40) CHECK (retirement_reason_code IS NULL OR retirement_reason_code ~ '^[A-Z_]{1,40}$'),
    UNIQUE (merchant_id, id),
    CHECK ((state = 'ACTIVE' AND retired_at IS NULL) OR (state = 'RETIRED' AND retired_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_merchant_webhook_active ON merchant_webhook_endpoints(merchant_id) WHERE state = 'ACTIVE';

CREATE TABLE merchant_webhook_deliveries (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    endpoint_id UUID NOT NULL,
    notification_key VARCHAR(100) NOT NULL,
    event_type VARCHAR(40) NOT NULL CHECK (event_type IN ('payment.authorized', 'payment.declined', 'payment.failed', 'payment.captured', 'refund.completed')),
    event_version INTEGER NOT NULL CHECK (event_version = 1),
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    refund_id UUID REFERENCES refunds(id) ON DELETE RESTRICT,
    source_event_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 4096),
    state VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'IN_FLIGHT', 'DELIVERED', 'TERMINAL', 'CANCELLED', 'EXHAUSTED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    next_attempt_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    current_attempt_id UUID,
    claim_deadline TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_result VARCHAR(40) CHECK (last_result IS NULL OR last_result ~ '^[A-Z_]{1,40}$'),
    FOREIGN KEY (merchant_id, endpoint_id) REFERENCES merchant_webhook_endpoints(merchant_id, id) ON DELETE RESTRICT,
    UNIQUE (merchant_id, notification_key),
    CHECK (expires_at = occurred_at + INTERVAL '24 hours'),
    CHECK ((event_type = 'refund.completed' AND refund_id IS NOT NULL AND notification_key = 'refund:' || refund_id::TEXT)
        OR (event_type = 'payment.captured' AND refund_id IS NULL AND notification_key = 'capture:' || payment_id::TEXT)
        OR (event_type IN ('payment.authorized', 'payment.declined', 'payment.failed') AND refund_id IS NULL AND notification_key = 'authorization:' || payment_id::TEXT)),
    CHECK ((state = 'IN_FLIGHT' AND current_attempt_id IS NOT NULL AND claim_deadline IS NOT NULL AND attempts > 0)
        OR (state <> 'IN_FLIGHT' AND current_attempt_id IS NULL AND claim_deadline IS NULL)),
    CHECK ((state = 'PENDING' AND next_attempt_at IS NOT NULL) OR (state <> 'PENDING' AND next_attempt_at IS NULL)),
    CHECK ((state = 'DELIVERED' AND delivered_at IS NOT NULL) OR (state <> 'DELIVERED' AND delivered_at IS NULL))
);
CREATE INDEX ix_webhook_due ON merchant_webhook_deliveries(next_attempt_at, id) WHERE state = 'PENDING';
CREATE INDEX ix_webhook_claim_expiry ON merchant_webhook_deliveries(claim_deadline, id) WHERE state = 'IN_FLIGHT';
CREATE INDEX ix_webhook_merchant_created ON merchant_webhook_deliveries(merchant_id, created_at DESC, id DESC);

CREATE TABLE merchant_webhook_attempts (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES merchant_webhook_deliveries(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 8),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    deadline TIMESTAMPTZ NOT NULL CHECK (deadline > started_at),
    key_id UUID NOT NULL,
    result VARCHAR(40) NOT NULL CHECK (result ~ '^[A-Z_]{1,40}$'),
    http_status INTEGER CHECK (http_status BETWEEN 100 AND 599),
    duration_ms BIGINT CHECK (duration_ms >= 0),
    UNIQUE (delivery_id, ordinal),
    UNIQUE (delivery_id, id),
    CHECK ((result = 'STARTED' AND completed_at IS NULL AND duration_ms IS NULL)
        OR (result <> 'STARTED' AND completed_at IS NOT NULL AND duration_ms IS NOT NULL))
);
ALTER TABLE merchant_webhook_deliveries ADD CONSTRAINT fk_webhook_current_attempt
    FOREIGN KEY (id, current_attempt_id) REFERENCES merchant_webhook_attempts(delivery_id, id)
    DEFERRABLE INITIALLY DEFERRED;

-- Updating a revision never redirects queued payloads; retirement is one-way.
CREATE FUNCTION protect_webhook_endpoint_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.merchant_id, NEW.url, NEW.hostname, NEW.path, NEW.pins, NEW.key_id,
        NEW.secret_ref, NEW.created_by, NEW.reason_code, NEW.created_at)
        IS DISTINCT FROM
       (OLD.id, OLD.merchant_id, OLD.url, OLD.hostname, OLD.path, OLD.pins, OLD.key_id,
        OLD.secret_ref, OLD.created_by, OLD.reason_code, OLD.created_at)
       OR OLD.state = 'RETIRED' AND (NEW.state, NEW.retired_at, NEW.retired_by, NEW.retirement_reason_code)
           IS DISTINCT FROM (OLD.state, OLD.retired_at, OLD.retired_by, OLD.retirement_reason_code) THEN
        RAISE EXCEPTION 'Webhook endpoint revision is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER webhook_endpoint_immutable BEFORE UPDATE ON merchant_webhook_endpoints
    FOR EACH ROW EXECUTE FUNCTION protect_webhook_endpoint_revision();

CREATE FUNCTION protect_webhook_delivery_identity() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.id, NEW.event_id, NEW.merchant_id, NEW.endpoint_id, NEW.notification_key,
            NEW.event_type, NEW.event_version, NEW.payment_id, NEW.refund_id, NEW.source_event_id,
            NEW.occurred_at, NEW.created_at, NEW.payload, NEW.expires_at)
            IS DISTINCT FROM
           (OLD.id, OLD.event_id, OLD.merchant_id, OLD.endpoint_id, OLD.notification_key,
            OLD.event_type, OLD.event_version, OLD.payment_id, OLD.refund_id, OLD.source_event_id,
            OLD.occurred_at, OLD.created_at, OLD.payload, OLD.expires_at) THEN
            RAISE EXCEPTION 'Webhook delivery identity is immutable' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM payments p WHERE p.id = NEW.payment_id AND p.merchant_id = NEW.merchant_id)
            OR NEW.refund_id IS NOT NULL AND NOT EXISTS
                (SELECT 1 FROM refunds r WHERE r.id = NEW.refund_id AND r.payment_id = NEW.payment_id AND r.merchant_id = NEW.merchant_id) THEN
            RAISE EXCEPTION 'Webhook source ownership conflict' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER webhook_delivery_immutable BEFORE INSERT OR UPDATE ON merchant_webhook_deliveries
    FOR EACH ROW EXECUTE FUNCTION protect_webhook_delivery_identity();

CREATE FUNCTION protect_webhook_attempt_identity() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.id, NEW.delivery_id, NEW.ordinal, NEW.started_at, NEW.deadline, NEW.key_id)
            IS DISTINCT FROM (OLD.id, OLD.delivery_id, OLD.ordinal, OLD.started_at, OLD.deadline, OLD.key_id)
            OR OLD.result <> 'STARTED' AND NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'Webhook attempt evidence is immutable' USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM merchant_webhook_deliveries d JOIN merchant_webhook_endpoints e
                ON d.endpoint_id = e.id AND d.merchant_id = e.merchant_id
                WHERE d.id = NEW.delivery_id AND e.key_id = NEW.key_id) THEN
            RAISE EXCEPTION 'Webhook attempt key ownership conflict' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER webhook_attempt_immutable BEFORE INSERT OR UPDATE ON merchant_webhook_attempts
    FOR EACH ROW EXECUTE FUNCTION protect_webhook_attempt_identity();
