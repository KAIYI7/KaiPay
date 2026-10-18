-- New default units only. Existing financial rows retain their original currency and amounts.
-- Application guards reject incompatible historical account reuse and new non-MYR activity.
ALTER TABLE accounts ALTER COLUMN currency SET DEFAULT 'MYR';
ALTER TABLE ledger_entries ALTER COLUMN currency SET DEFAULT 'MYR';
ALTER TABLE refunds ALTER COLUMN currency SET DEFAULT 'MYR';
