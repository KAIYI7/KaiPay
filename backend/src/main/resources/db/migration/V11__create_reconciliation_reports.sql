-- Completed, immutable application audit reports. No financial source changes or backfill.
CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    scope VARCHAR(30) NOT NULL CHECK (scope = 'AUTHORIZATION_V1'),
    ruleset VARCHAR(40) NOT NULL CHECK (ruleset = 'AUTHORIZATION_V1.0'),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    build_revision VARCHAR(100) NOT NULL,
    from_inclusive TIMESTAMPTZ NOT NULL,
    cutoff_exclusive TIMESTAMPTZ NOT NULL,
    grace_seconds INTEGER NOT NULL CHECK (grace_seconds = 300),
    payment_limit INTEGER NOT NULL CHECK (payment_limit = 1000),
    execution_limit_seconds INTEGER NOT NULL CHECK (execution_limit_seconds = 15),
    actor VARCHAR(100) NOT NULL CHECK (actor ~ '^[A-Za-z0-9_-]{1,100}$'),
    reason VARCHAR(30) NOT NULL CHECK (reason IN ('DEMO_REVIEW','INCIDENT_REVIEW','VERIFICATION')),
    input_fingerprint VARCHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[0-9a-f]{64}$'),
    snapshot_observed_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    consumer_group VARCHAR(100) NOT NULL CHECK (length(consumer_group) BETWEEN 1 AND 100),
    cohort_count INTEGER NOT NULL CHECK (cohort_count BETWEEN 0 AND 1000),
    counts JSONB NOT NULL CHECK (jsonb_typeof(counts) = 'object'),
    report JSONB NOT NULL CHECK (jsonb_typeof(report) = 'object'),
    UNIQUE(id, merchant_id),
    CHECK (from_inclusive < cutoff_exclusive AND cutoff_exclusive - from_inclusive <= INTERVAL '7 days')
);
CREATE INDEX idx_reconciliation_runs_merchant ON reconciliation_runs(merchant_id,snapshot_observed_at DESC,id DESC);

CREATE TABLE reconciliation_observations (
    run_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    primary_class VARCHAR(40) NOT NULL CHECK (primary_class IN ('OWNERSHIP_CONFLICT','EVIDENCE_INVALID','REQUEST_MISMATCH',
        'AUTHORIZATION_MISMATCH','INTENT_STATE_CONFLICT','PENDING_FINALIZATION','FINALIZATION_OUTSTANDING',
        'UNRESOLVED','INTENT_PENDING','QUEUED','EVIDENCE_UNAVAILABLE','AMBIGUOUS_PROCESSING','MATCHED')),
    severity VARCHAR(10) NOT NULL CHECK (severity IN ('INFO','WARNING','ERROR')),
    reason_codes JSONB NOT NULL CHECK (jsonb_typeof(reason_codes) = 'array' AND jsonb_array_length(reason_codes) <= 16),
    evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence) = 'object'),
    explanation VARCHAR(1000) NOT NULL,
    observation JSONB NOT NULL CHECK (jsonb_typeof(observation) = 'object' AND octet_length(observation::text) <= 16384),
    PRIMARY KEY(run_id,payment_id),
    FOREIGN KEY(run_id,merchant_id) REFERENCES reconciliation_runs(id,merchant_id) ON DELETE RESTRICT
);
CREATE INDEX idx_reconciliation_observations_merchant ON reconciliation_observations(merchant_id,run_id,primary_class,payment_id);
