-- =============================================================================
-- KaiPay V4: Idempotent Consumer & Dead Letter Topic (DLT) Audit Schema
-- Enables idempotency tracking and dead-letter event persistence
-- =============================================================================

CREATE TABLE consumed_events (
    event_id UUID NOT NULL,
    consumer_group VARCHAR(100) NOT NULL,
    payment_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_consumed_events PRIMARY KEY (event_id, consumer_group)
);

CREATE INDEX idx_consumed_events_payment ON consumed_events (payment_id);

CREATE TABLE dead_letter_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    original_topic VARCHAR(100) NOT NULL,
    original_partition INT NOT NULL,
    original_offset BIGINT NOT NULL,
    event_id UUID,
    payment_id UUID,
    exception_class VARCHAR(255) NOT NULL,
    failure_message TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dlt_payment ON dead_letter_events (payment_id);
CREATE INDEX idx_dlt_created ON dead_letter_events (created_at DESC);
