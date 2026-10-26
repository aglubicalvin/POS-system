-- Canonical PostgreSQL schema for the POS server.
--
-- This file is the SINGLE source of truth for the Postgres dialect. It is loaded
-- by the Kotlin server at startup AND read by the TypeScript test suite
-- (tests/schema.test.ts), which compares it against the device SQLite schema.
-- One file, two languages reading it, so the dialects cannot silently diverge.
--
-- Type affinity mapping from the device dialect:
--   TEXT (ISO-8601)   -> TIMESTAMPTZ
--   INTEGER 0/1       -> BOOLEAN
--   INTEGER money/qty -> BIGINT   (GHS 21.4M overflows Postgres INTEGER)
--   TEXT json         -> JSONB
-- Table and column NAMES are identical to the SQLite dialect.

CREATE TABLE IF NOT EXISTS devices (
  device_id        TEXT PRIMARY KEY,
  store_id         TEXT NOT NULL,
  name             TEXT NOT NULL,
  model            TEXT,
  registered_at    TIMESTAMPTZ NOT NULL,
  last_seen_at     TIMESTAMPTZ,
  token_expires_at TIMESTAMPTZ,
  is_active        BOOLEAN NOT NULL DEFAULT TRUE
);

-- Hashed, short-lived device tokens. The raw token is returned once at
-- registration/rotation and never persisted.
CREATE TABLE IF NOT EXISTS device_tokens (
  token_hash TEXT PRIMARY KEY,
  device_id  TEXT NOT NULL REFERENCES devices(device_id),
  issued_at  TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS users (
  user_id         TEXT PRIMARY KEY,
  device_id       TEXT REFERENCES devices(device_id),
  full_name       TEXT NOT NULL,
  role            TEXT NOT NULL CHECK (role IN ('cashier', 'manager', 'admin')),
  pin_hash        TEXT,
  password_hash   TEXT,
  mfa_enabled     BOOLEAN NOT NULL DEFAULT FALSE,
  failed_attempts BIGINT NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  locked_until    TIMESTAMPTZ,
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS suppliers (
  supplier_id TEXT PRIMARY KEY,
  device_id   TEXT NOT NULL,
  name        TEXT NOT NULL,
  phone       TEXT,
  email       TEXT,
  is_active   BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS products (
  product_id          TEXT PRIMARY KEY,
  device_id           TEXT NOT NULL,
  sku                 TEXT NOT NULL UNIQUE,
  name                TEXT NOT NULL,
  unit_price          BIGINT NOT NULL CHECK (unit_price >= 0),
  cost_price          BIGINT NOT NULL DEFAULT 0 CHECK (cost_price >= 0),
  tax_rate_bp         BIGINT NOT NULL DEFAULT 0 CHECK (tax_rate_bp >= 0 AND tax_rate_bp <= 100000),
  low_stock_threshold BIGINT NOT NULL DEFAULT 0 CHECK (low_stock_threshold >= 0),
  is_active           BOOLEAN NOT NULL DEFAULT TRUE,
  version             BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at          TIMESTAMPTZ NOT NULL,
  updated_at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS goods_received (
  grn_id      TEXT PRIMARY KEY,
  device_id   TEXT NOT NULL,
  supplier_id TEXT NOT NULL REFERENCES suppliers(supplier_id),
  reference   TEXT,
  total_cost  BIGINT NOT NULL DEFAULT 0 CHECK (total_cost >= 0),
  status      TEXT NOT NULL DEFAULT 'received' CHECK (status IN ('draft', 'received', 'cancelled')),
  received_by TEXT NOT NULL REFERENCES users(user_id),
  received_at TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS goods_received_items (
  item_id    TEXT PRIMARY KEY,
  grn_id     TEXT NOT NULL REFERENCES goods_received(grn_id),
  product_id TEXT NOT NULL REFERENCES products(product_id),
  quantity   BIGINT NOT NULL CHECK (quantity > 0),
  unit_cost  BIGINT NOT NULL CHECK (unit_cost >= 0)
);

CREATE TABLE IF NOT EXISTS shifts (
  shift_id      TEXT PRIMARY KEY,
  device_id     TEXT NOT NULL,
  user_id       TEXT NOT NULL REFERENCES users(user_id),
  opened_at     TIMESTAMPTZ NOT NULL,
  closed_at     TIMESTAMPTZ,
  opening_float BIGINT NOT NULL DEFAULT 0 CHECK (opening_float >= 0),
  cash_count    BIGINT,
  expected_cash BIGINT,
  variance      BIGINT,
  status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
  approved_by   TEXT
);

CREATE TABLE IF NOT EXISTS sales (
  sale_id           TEXT PRIMARY KEY,
  device_id         TEXT NOT NULL,
  receipt_number    TEXT NOT NULL,
  shift_id          TEXT REFERENCES shifts(shift_id),
  cashier_id        TEXT NOT NULL REFERENCES users(user_id),
  customer_ref      TEXT,
  currency          TEXT NOT NULL DEFAULT 'GHS',
  subtotal          BIGINT NOT NULL CHECK (subtotal >= 0),
  discount_total    BIGINT NOT NULL DEFAULT 0 CHECK (discount_total >= 0),
  tax_total         BIGINT NOT NULL DEFAULT 0 CHECK (tax_total >= 0),
  total             BIGINT NOT NULL CHECK (total >= 0),
  kind              TEXT NOT NULL DEFAULT 'sale' CHECK (kind IN ('sale', 'void', 'refund')),
  original_sale_id  TEXT REFERENCES sales(sale_id),
  status            TEXT NOT NULL DEFAULT 'completed' CHECK (status IN ('completed', 'voided', 'refunded')),
  voided_by         TEXT,
  voided_reason     TEXT,
  server_total      BIGINT,
  price_discrepancy BOOLEAN NOT NULL DEFAULT FALSE,
  sync_state        TEXT NOT NULL DEFAULT 'pending' CHECK (sync_state IN ('pending', 'synced', 'disputed')),
  created_at        TIMESTAMPTZ NOT NULL,
  UNIQUE (device_id, receipt_number)
);

CREATE TABLE IF NOT EXISTS sale_items (
  item_id      TEXT PRIMARY KEY,
  sale_id      TEXT NOT NULL REFERENCES sales(sale_id),
  product_id   TEXT NOT NULL REFERENCES products(product_id),
  sku          TEXT NOT NULL,
  name         TEXT NOT NULL,
  quantity     BIGINT NOT NULL CHECK (quantity > 0),
  unit_price   BIGINT NOT NULL CHECK (unit_price >= 0),
  discount     BIGINT NOT NULL DEFAULT 0 CHECK (discount >= 0),
  tax_rate_bp  BIGINT NOT NULL DEFAULT 0 CHECK (tax_rate_bp >= 0),
  tax          BIGINT NOT NULL DEFAULT 0 CHECK (tax >= 0),
  line_total   BIGINT NOT NULL,
  price_source TEXT NOT NULL DEFAULT 'catalog' CHECK (price_source IN ('catalog', 'override')),
  override_by  TEXT
);

CREATE TABLE IF NOT EXISTS payments (
  payment_id      TEXT PRIMARY KEY,
  sale_id         TEXT NOT NULL REFERENCES sales(sale_id),
  method          TEXT NOT NULL CHECK (method IN ('cash', 'mobile_money', 'card', 'credit')),
  amount          BIGINT NOT NULL CHECK (amount > 0),
  status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'confirmed', 'failed')),
  idempotency_key TEXT NOT NULL UNIQUE,
  provider        TEXT,
  provider_ref    TEXT,
  failure_reason  TEXT,
  reconciled_at   TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS stock_movements (
  movement_id     TEXT PRIMARY KEY,
  device_id       TEXT NOT NULL,
  product_id      TEXT NOT NULL REFERENCES products(product_id),
  quantity_delta  BIGINT NOT NULL CHECK (quantity_delta <> 0),
  reason_code     TEXT NOT NULL CHECK (reason_code IN (
                    'sale', 'goods_received', 'adjustment', 'disbursement',
                    'refund', 'void', 'transfer_out', 'transfer_in', 'stocktake')),
  ref_type        TEXT,
  ref_id          TEXT,
  user_id         TEXT NOT NULL,
  approval_id     TEXT,
  requires_review BOOLEAN NOT NULL DEFAULT FALSE,
  occurred_at     TIMESTAMPTZ NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS stock_on_hand (
  product_id TEXT PRIMARY KEY REFERENCES products(product_id),
  quantity   BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS receipt_counters (
  device_id TEXT NOT NULL,
  day       TEXT NOT NULL,
  last_seq  BIGINT NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
  PRIMARY KEY (device_id, day)
);

CREATE TABLE IF NOT EXISTS outbox (
  event_id        TEXT PRIMARY KEY,
  device_id       TEXT NOT NULL,
  seq             BIGINT NOT NULL,
  event_type      TEXT NOT NULL,
  aggregate_type  TEXT NOT NULL,
  aggregate_id    TEXT NOT NULL,
  payload         JSONB NOT NULL,
  idempotency_key TEXT NOT NULL UNIQUE,
  status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'acked', 'failed')),
  attempts        BIGINT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error      TEXT,
  created_at      TIMESTAMPTZ NOT NULL,
  sent_at         TIMESTAMPTZ,
  acked_at        TIMESTAMPTZ,
  UNIQUE (device_id, seq)
);

CREATE TABLE IF NOT EXISTS processed_events (
  event_id       TEXT PRIMARY KEY,
  device_id      TEXT NOT NULL,
  seq            BIGINT NOT NULL,
  event_type     TEXT NOT NULL,
  aggregate_type TEXT,
  aggregate_id   TEXT,
  payload        JSONB NOT NULL,
  applied_at     TIMESTAMPTZ NOT NULL,
  result         JSONB NOT NULL
);

CREATE TABLE IF NOT EXISTS approvals (
  approval_id  TEXT PRIMARY KEY,
  device_id    TEXT NOT NULL,
  requested_by TEXT NOT NULL,
  approved_by  TEXT NOT NULL REFERENCES users(user_id),
  action       TEXT NOT NULL,
  entity_type  TEXT,
  entity_id    TEXT,
  reason       TEXT,
  created_at   TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
  audit_id    TEXT PRIMARY KEY,
  device_id   TEXT NOT NULL,
  seq         BIGINT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  actor_id    TEXT,
  action      TEXT NOT NULL,
  entity_type TEXT,
  entity_id   TEXT,
  details     JSONB,
  prev_hash   TEXT NOT NULL,
  hash        TEXT NOT NULL,
  UNIQUE (device_id, seq)
);

-- Ordered-sync state for the event ingest path (src/sync/ordering.ts on the
-- device side). last_applied_seq is the contiguous ack watermark; pending_events
-- buffers gap events until the missing seq arrives; rejected_events logs
-- permanently invalid payloads with the reason.
CREATE TABLE IF NOT EXISTS device_sync_state (
  device_id        TEXT PRIMARY KEY,
  last_applied_seq BIGINT NOT NULL DEFAULT 0,
  last_hash        TEXT
);

CREATE TABLE IF NOT EXISTS pending_events (
  device_id   TEXT NOT NULL,
  seq         BIGINT NOT NULL,
  payload     JSONB NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (device_id, seq)
);

CREATE TABLE IF NOT EXISTS rejected_events (
  device_id   TEXT NOT NULL,
  seq         BIGINT NOT NULL,
  reason      TEXT NOT NULL,
  payload     JSONB NOT NULL,
  rejected_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (device_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_sales_device_created ON sales (device_id, created_at);
CREATE INDEX IF NOT EXISTS idx_sale_items_sale ON sale_items (sale_id);
CREATE INDEX IF NOT EXISTS idx_payments_sale ON payments (sale_id);
CREATE INDEX IF NOT EXISTS idx_payments_status ON payments (status);
CREATE INDEX IF NOT EXISTS idx_stock_movements_product ON stock_movements (product_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_outbox_status ON outbox (status, device_id, seq);
CREATE INDEX IF NOT EXISTS idx_audit_device_seq ON audit_log (device_id, seq);
CREATE INDEX IF NOT EXISTS idx_device_tokens_device ON device_tokens (device_id);
