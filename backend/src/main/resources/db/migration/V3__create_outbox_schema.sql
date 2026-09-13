-- =============================================================================
-- KaiPay V3: Transactional Outbox Pattern Schema
-- Enables reliable asynchronous event publishing via PostgreSQL & Kafka
-- =============================================================================

CREATE TABLE payment_events_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ
);

-- Partial index for high-throughput outbox polling (only unhandled events)
CREATE INDEX idx_outbox_pending ON payment_events_outbox (created_at ASC) WHERE status = 'PENDING';

-- Index for aggregate-level history querying and correlation
CREATE INDEX idx_outbox_aggregate ON payment_events_outbox (aggregate_type, aggregate_id);
