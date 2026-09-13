-- =============================================================================
-- KaiPay V2: Seed Pre-Configured Development Merchants & Customers
-- Enables Out-of-the-Box Simulation Mode for the React Frontend
-- =============================================================================

-- 1. Merchant A (Acme E-Commerce Corp)
INSERT INTO merchants (id, name, api_key_hash, webhook_url, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'Acme E-Commerce Corp', 'acme_live_hash_secret_1111', 'https://acme.example.com/webhook', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- Customers for Merchant A
INSERT INTO customers (id, merchant_id, email, full_name)
VALUES
  ('22222222-2222-2222-2222-222222222221', '11111111-1111-1111-1111-111111111111', 'alice@example.com', 'Alice Wonderland'),
  ('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', 'bob@example.com', 'Bob Miller')
ON CONFLICT (id) DO NOTHING;

-- 2. Merchant B (Global Retailers Ltd)
INSERT INTO merchants (id, name, api_key_hash, webhook_url, status)
VALUES ('33333333-3333-3333-3333-333333333333', 'Global Retailers Ltd', 'global_live_hash_secret_3333', 'https://global.example.com/webhook', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- Customer for Merchant B
INSERT INTO customers (id, merchant_id, email, full_name)
VALUES
  ('44444444-4444-4444-4444-444444444441', '33333333-3333-3333-3333-333333333333', 'charlie@example.com', 'Charlie Brown')
ON CONFLICT (id) DO NOTHING;
