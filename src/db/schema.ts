/**
 * Portable relational schema for the POS device (SQLite).
 *
 * The PostgreSQL dialect for the server lives in ONE place —
 * `server/src/main/resources/db/schema.sql` — and is owned by the Kotlin backend.
 * `tests/schema.test.ts` reads that file and asserts this SQLite schema has the
 * same tables, columns and indexes, so the two dialects cannot drift.
 *
 * Design rules that let the same table + constraint definitions run on both:
 *
 *   - Surrogate keys are client-generated UUID TEXT values (no AUTOINCREMENT /
 *     SERIAL), so a record created offline keeps its identity after sync.
 *   - Timestamps are ISO-8601 UTC strings in TEXT on SQLite (TIMESTAMPTZ on PG).
 *   - Money is INTEGER minor units (pesewas for GHS). Never float.
 *   - Booleans are INTEGER constrained to (0,1); never the BOOLEAN keyword.
 *   - Structured payloads are TEXT JSON on SQLite (JSONB on PG).
 *   - Everything is nullable-explicit and CHECK/UNIQUE/FK constrained, which
 *     both engines enforce.
 */

export const SCHEMA_SQLITE = `
CREATE TABLE IF NOT EXISTS devices (
  device_id        TEXT PRIMARY KEY,
  store_id         TEXT NOT NULL,
  name             TEXT NOT NULL,
  model            TEXT,
  registered_at    TEXT NOT NULL,
  last_seen_at     TEXT,
  token_expires_at TEXT,
  is_active        INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1))
);

-- Hashed, short-lived device tokens. The raw token is returned once at
-- registration/rotation and never persisted, here or on the server.
CREATE TABLE IF NOT EXISTS device_tokens (
  token_hash TEXT PRIMARY KEY,
  device_id  TEXT NOT NULL,
  issued_at  TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE TABLE IF NOT EXISTS users (
  user_id         TEXT PRIMARY KEY,
  device_id       TEXT,
  full_name       TEXT NOT NULL,
  role            TEXT NOT NULL CHECK (role IN ('cashier', 'manager', 'admin')),
  pin_hash        TEXT,
  password_hash   TEXT,
  mfa_enabled     INTEGER NOT NULL DEFAULT 0 CHECK (mfa_enabled IN (0, 1)),
  failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  locked_until    TEXT,
  is_active       INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL,
  FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE TABLE IF NOT EXISTS suppliers (
  supplier_id TEXT PRIMARY KEY,
  device_id   TEXT NOT NULL,
  name        TEXT NOT NULL,
  phone       TEXT,
  email       TEXT,
  is_active   INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS products (
  product_id          TEXT PRIMARY KEY,
  device_id           TEXT NOT NULL,
  sku                 TEXT NOT NULL,
  name                TEXT NOT NULL,
  unit_price          INTEGER NOT NULL CHECK (unit_price >= 0),
  cost_price          INTEGER NOT NULL DEFAULT 0 CHECK (cost_price >= 0),
  tax_rate_bp         INTEGER NOT NULL DEFAULT 0 CHECK (tax_rate_bp >= 0 AND tax_rate_bp <= 100000),
  low_stock_threshold INTEGER NOT NULL DEFAULT 0 CHECK (low_stock_threshold >= 0),
  is_active           INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
  version             INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at          TEXT NOT NULL,
  updated_at          TEXT NOT NULL,
  UNIQUE (sku)
);

CREATE TABLE IF NOT EXISTS goods_received (
  grn_id      TEXT PRIMARY KEY,
  device_id   TEXT NOT NULL,
  supplier_id TEXT NOT NULL,
  reference   TEXT,
  total_cost  INTEGER NOT NULL DEFAULT 0 CHECK (total_cost >= 0),
  status      TEXT NOT NULL DEFAULT 'received' CHECK (status IN ('draft', 'received', 'cancelled')),
  received_by TEXT NOT NULL,
  received_at TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id),
  FOREIGN KEY (received_by) REFERENCES users(user_id)
);

CREATE TABLE IF NOT EXISTS goods_received_items (
  item_id    TEXT PRIMARY KEY,
  grn_id     TEXT NOT NULL,
  product_id TEXT NOT NULL,
  quantity   INTEGER NOT NULL CHECK (quantity > 0),
  unit_cost  INTEGER NOT NULL CHECK (unit_cost >= 0),
  FOREIGN KEY (grn_id) REFERENCES goods_received(grn_id),
  FOREIGN KEY (product_id) REFERENCES products(product_id)
);

CREATE TABLE IF NOT EXISTS shifts (
  shift_id      TEXT PRIMARY KEY,
  device_id     TEXT NOT NULL,
  user_id       TEXT NOT NULL,
  opened_at     TEXT NOT NULL,
  closed_at     TEXT,
  opening_float INTEGER NOT NULL DEFAULT 0 CHECK (opening_float >= 0),
  cash_count    INTEGER,
  expected_cash INTEGER,
  variance      INTEGER,
  status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
  approved_by   TEXT,
  FOREIGN KEY (user_id) REFERENCES users(user_id)
);

CREATE TABLE IF NOT EXISTS sales (
  sale_id          TEXT PRIMARY KEY,
  device_id        TEXT NOT NULL,
  receipt_number   TEXT NOT NULL,
  shift_id         TEXT,
  cashier_id       TEXT NOT NULL,
  customer_ref     TEXT,
  currency         TEXT NOT NULL DEFAULT 'GHS',
  subtotal         INTEGER NOT NULL CHECK (subtotal >= 0),
  discount_total   INTEGER NOT NULL DEFAULT 0 CHECK (discount_total >= 0),
  tax_total        INTEGER NOT NULL DEFAULT 0 CHECK (tax_total >= 0),
  total            INTEGER NOT NULL CHECK (total >= 0),
  kind             TEXT NOT NULL DEFAULT 'sale' CHECK (kind IN ('sale', 'void', 'refund')),
  original_sale_id TEXT,
  status           TEXT NOT NULL DEFAULT 'completed' CHECK (status IN ('completed', 'voided', 'refunded')),
  voided_by        TEXT,
  voided_reason    TEXT,
  server_total     INTEGER,
  price_discrepancy INTEGER NOT NULL DEFAULT 0 CHECK (price_discrepancy IN (0, 1)),
  sync_state       TEXT NOT NULL DEFAULT 'pending' CHECK (sync_state IN ('pending', 'synced', 'disputed')),
  created_at       TEXT NOT NULL,
  UNIQUE (device_id, receipt_number),
  FOREIGN KEY (shift_id) REFERENCES shifts(shift_id),
  FOREIGN KEY (cashier_id) REFERENCES users(user_id),
  FOREIGN KEY (original_sale_id) REFERENCES sales(sale_id)
);

CREATE TABLE IF NOT EXISTS sale_items (
  item_id      TEXT PRIMARY KEY,
  sale_id      TEXT NOT NULL,
  product_id   TEXT NOT NULL,
  sku          TEXT NOT NULL,
  name         TEXT NOT NULL,
  quantity     INTEGER NOT NULL CHECK (quantity > 0),
  unit_price   INTEGER NOT NULL CHECK (unit_price >= 0),
  discount     INTEGER NOT NULL DEFAULT 0 CHECK (discount >= 0),
  tax_rate_bp  INTEGER NOT NULL DEFAULT 0 CHECK (tax_rate_bp >= 0),
  tax          INTEGER NOT NULL DEFAULT 0 CHECK (tax >= 0),
  line_total   INTEGER NOT NULL,
  price_source TEXT NOT NULL DEFAULT 'catalog' CHECK (price_source IN ('catalog', 'override')),
  override_by  TEXT,
  FOREIGN KEY (sale_id) REFERENCES sales(sale_id),
  FOREIGN KEY (product_id) REFERENCES products(product_id)
);

CREATE TABLE IF NOT EXISTS payments (
  payment_id      TEXT PRIMARY KEY,
  sale_id         TEXT NOT NULL,
  method          TEXT NOT NULL CHECK (method IN ('cash', 'mobile_money', 'card', 'credit')),
  amount          INTEGER NOT NULL CHECK (amount > 0),
  status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'confirmed', 'failed')),
  idempotency_key TEXT NOT NULL,
  provider        TEXT,
  provider_ref    TEXT,
  failure_reason  TEXT,
  reconciled_at   TEXT,
  created_at      TEXT NOT NULL,
  updated_at      TEXT NOT NULL,
  UNIQUE (idempotency_key),
  FOREIGN KEY (sale_id) REFERENCES sales(sale_id)
);

-- Source of truth for every quantity change. stock_on_hand is a transactionally
-- maintained cache for fast lookups and can be rebuilt from this table.
CREATE TABLE IF NOT EXISTS stock_movements (
  movement_id   TEXT PRIMARY KEY,
  device_id     TEXT NOT NULL,
  product_id    TEXT NOT NULL,
  quantity_delta INTEGER NOT NULL CHECK (quantity_delta <> 0),
  reason_code   TEXT NOT NULL CHECK (reason_code IN (
                  'sale', 'goods_received', 'adjustment', 'disbursement',
                  'refund', 'void', 'transfer_out', 'transfer_in', 'stocktake')),
  ref_type      TEXT,
  ref_id        TEXT,
  user_id       TEXT NOT NULL,
  approval_id   TEXT,
  requires_review INTEGER NOT NULL DEFAULT 0 CHECK (requires_review IN (0, 1)),
  occurred_at   TEXT NOT NULL,
  created_at    TEXT NOT NULL,
  FOREIGN KEY (product_id) REFERENCES products(product_id)
);

CREATE TABLE IF NOT EXISTS stock_on_hand (
  product_id TEXT PRIMARY KEY,
  quantity   INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (product_id) REFERENCES products(product_id)
);

-- Per-device, per-day receipt sequence. Bumped inside the checkout transaction
-- so DEVICE-DATE-SEQ numbers are gapless on a device even while offline.
CREATE TABLE IF NOT EXISTS receipt_counters (
  device_id TEXT NOT NULL,
  day       TEXT NOT NULL,
  last_seq  INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
  PRIMARY KEY (device_id, day)
);

-- Local durable outbox. Per-device monotonic seq gives the server a total order;
-- the idempotency_key makes retried uploads safe.
CREATE TABLE IF NOT EXISTS outbox (
  event_id        TEXT PRIMARY KEY,
  device_id       TEXT NOT NULL,
  seq             INTEGER NOT NULL,
  event_type      TEXT NOT NULL,
  aggregate_type  TEXT NOT NULL,
  aggregate_id    TEXT NOT NULL,
  payload         TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'acked', 'failed')),
  attempts        INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  last_error      TEXT,
  created_at      TEXT NOT NULL,
  sent_at         TEXT,
  acked_at        TEXT,
  UNIQUE (device_id, seq),
  UNIQUE (idempotency_key)
);

-- Server-side ledger of applied events. Presence of event_id means "already
-- applied"; the stored envelope lets GET /sync/pull relay the event verbatim to
-- the other tills of the store. Mirrored on the device dialect so both schemas
-- stay identical.
CREATE TABLE IF NOT EXISTS processed_events (
  event_id       TEXT PRIMARY KEY,
  device_id      TEXT NOT NULL,
  seq            INTEGER NOT NULL,
  event_type     TEXT NOT NULL,
  aggregate_type TEXT,
  aggregate_id   TEXT,
  payload        TEXT NOT NULL,
  applied_at     TEXT NOT NULL,
  result         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS approvals (
  approval_id  TEXT PRIMARY KEY,
  device_id    TEXT NOT NULL,
  requested_by TEXT NOT NULL,
  approved_by  TEXT NOT NULL,
  action       TEXT NOT NULL,
  entity_type  TEXT,
  entity_id    TEXT,
  reason       TEXT,
  created_at   TEXT NOT NULL,
  FOREIGN KEY (approved_by) REFERENCES users(user_id)
);

-- Tamper-evident, hash-chained audit log. hash = sha256(prev_hash | canonical row).
-- Any edit to a historical row breaks verification from that point forward.
CREATE TABLE IF NOT EXISTS audit_log (
  audit_id    TEXT PRIMARY KEY,
  device_id   TEXT NOT NULL,
  seq         INTEGER NOT NULL,
  occurred_at TEXT NOT NULL,
  actor_id    TEXT,
  action      TEXT NOT NULL,
  entity_type TEXT,
  entity_id   TEXT,
  details     TEXT,
  prev_hash   TEXT NOT NULL,
  hash        TEXT NOT NULL,
  UNIQUE (device_id, seq)
);

-- Ordered-sync state, used by src/sync/ordering.ts for replay-safe ingest.
-- last_applied_seq is the contiguous ack watermark on the device (mirrors the
-- server's processed_events ledger); pending_events buffers gap events until the
-- missing seq arrives; rejected_events logs permanently invalid payloads.
CREATE TABLE IF NOT EXISTS device_sync_state (
  device_id        TEXT PRIMARY KEY,
  last_applied_seq INTEGER NOT NULL DEFAULT 0,
  last_hash        TEXT
);

CREATE TABLE IF NOT EXISTS pending_events (
  device_id   TEXT NOT NULL,
  seq         INTEGER NOT NULL,
  payload     TEXT NOT NULL,
  received_at TEXT NOT NULL,
  PRIMARY KEY (device_id, seq)
);

CREATE TABLE IF NOT EXISTS rejected_events (
  device_id   TEXT NOT NULL,
  seq         INTEGER NOT NULL,
  reason      TEXT NOT NULL,
  payload     TEXT NOT NULL,
  rejected_at TEXT NOT NULL,
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
`;
