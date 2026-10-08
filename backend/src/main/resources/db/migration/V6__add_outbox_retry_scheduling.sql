-- Existing PENDING rows remain immediately eligible; no payload or status rewrite.
ALTER TABLE payment_events_outbox
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN last_attempt_at TIMESTAMPTZ,
    ADD COLUMN quarantined_at TIMESTAMPTZ;

DROP INDEX idx_outbox_pending;
CREATE INDEX idx_outbox_pending ON payment_events_outbox
    (COALESCE(next_attempt_at, created_at), created_at, id) WHERE status = 'PENDING';
