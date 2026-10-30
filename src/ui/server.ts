import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { get, run, type Db } from '../db/db.ts';
import { CheckoutError, checkout } from '../domain/checkout.ts';
import { createPayment, confirmPayment, type PaymentMethod } from '../domain/payments.ts';
import {
  findPaymentRow,
  initiateMobileMoneyPayment,
  MomoError,
  paymentStatusView,
  verifyMobileMoneyPayment,
  type MomoProvider,
} from '../domain/momo.ts';
import { listProducts, ProductError, type ProductRow } from '../domain/products.ts';
import { openShift, openShiftForDevice } from '../domain/shifts.ts';
import { PermissionError, type Role } from '../domain/permissions.ts';
import { verifyPin } from '../domain/users.ts';

/** 
 * The first slice of the Till UI: a cashier-facing web app over the REAL domain 
 * layer, so checkout is exactly the atomic, permission-checked, outbox-enqueued 
 * `checkout()` the sync tests exercise — the UI adds no business rules. 
 * 
 * Endpoints (JSON): 
 *   GET  /api/users            — active users with PINs (till is a trusted LAN device) 
 *   POST /api/login            — PIN gate with device binding + lockout (device id: 
 *                                ` TillUiOptions.deviceId` if the till has one, else body) 
 *   POST /api/logout           — forget the session 
 *   GET  /api/products         — active catalog with on-hand stock 
 *   GET  /api/shift            — the device's open shift, if any 
 *   POST /api/shift            — open a shift for the logged-in user 
 *   GET  /api/reports/sales  — sales report with optional from/to/cashier filters 
 *   POST /api/checkout         — atomic checkout; auto-attaches the device's open 
 *                                shift; a cash/card payment is confirmed in one
 *                                flow, a mobile-money attempt stays PENDING until
 *                                the provider verdict arrives
 *   POST /api/payments/mobile-money/initiate — hand a pending MoMo attempt to the
 *                                configured provider (idempotent; 503 without one)
 *   GET  /api/payments/:id     — cashier's status poll; verifying a pending MoMo
 *                                attempt against the provider's verdict
 *
 * Not in this slice yet (see docs/roadmap.md): voids/refunds, receipt printing,
 * push webhooks on the till host (the Kotlin server is the webhook endpoint),
 * manager overrides on price/discount approvals (beyond what the role matrix
 * permits), the Capacitor shell and an Android SQLite binding.
 */

export interface TillUiOptions {
  /** IP:port to bind; port 0 (default in tests) picks a free one. Defaults to 127.0.0.1. */
  port?: number;
  host?: string;
  /** This till's registered device id, when known. Login falls back to the body if absent. */
  deviceId?: string;
  /**
   * The till's mobile-money vendor adapter. Absent on a till that must not
   * initiate MoMo: initiation then fails closed (HTTP 503) and pending
   * attempts stay pending until a provider is attached.
   */
  momoProvider?: MomoProvider;
}

export interface TillUiHandle {
  server: Server;
  port: number;
  close(): Promise<void>;
}

interface Session {
  userId: string;
  fullName: string;
  role: Role;
  deviceId: string;
}

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

const STATIC_DIR = join(fileURLToPath(import.meta.url), '..', 'static');

export function startTillUi(db: Db, options: TillUiOptions = {}): Promise<TillUiHandle> {
  const sessions = new Map<string, Session>();

  const server = createServer((req, res) => {
    route(db, sessions, options.deviceId, options.momoProvider, req, res).catch((error) => {
      respondJson(res, 500, { error: error instanceof Error ? error.message : String(error) });
    });
  });

  return new Promise((resolvePromise, reject) => {
    server.once('error', reject);
    server.listen(options.port ?? 0, options.host ?? '127.0.0.1', () => {
      const address = server.address();
      if (!address || typeof address === 'string') reject(new Error('could not determine the bound port'));
      else resolvePromise({ server, port: address.port, close: () => closeQuietly(server) });
    });
  });
}

function closeQuietly(server: Server): Promise<void> {
  return new Promise((resolvePromise) => {
    server.close(() => resolvePromise());
  });
}

async function route(
  db: Db,
  sessions: Map<string, Session>,
  tillDeviceId: string | undefined,
  momoProvider: MomoProvider | undefined,
  req: IncomingMessage,
  res: ServerResponse,
): Promise<void> {
  const url = (req.url ?? '/').split('?')[0]!;
  const method = req.method ?? 'GET';

  if (method === 'GET' && !url.startsWith('/api/')) {
    return serveStatic(res, url === '/' ? 'index.html' : url.slice(1));
  }

  if (method === 'POST' && url === '/api/login') {
    const body = await readJson(req);
    const deviceId = tillDeviceId ?? body.deviceId;
    if (typeof deviceId !== 'string' || deviceId === '') {
      return respondJson(res, 400, { error: 'deviceId is required' });
    }
    const result = verifyPin(db, String(body.userId ?? ''), String(body.pin ?? ''), deviceId);
    if (!result.ok || !result.user) return respondJson(res, 401, { ok: false, reason: result.reason });
    const token = randomUUID();
    sessions.set(token, {
      userId: result.user.user_id,
      fullName: result.user.full_name,
      role: result.user.role,
      deviceId,
    });
    return respondJson(res, 200, {
      token,
      user: { userId: result.user.user_id, fullName: result.user.full_name, role: result.user.role },
    });
  }

  if (method === 'GET' && url === '/api/users') {
    const users = (
      db
        .prepare('SELECT user_id, full_name, role FROM users WHERE is_active = 1 AND pin_hash IS NOT NULL')
        .all() as Array<{ user_id: string; full_name: string; role: string }>
    ).map((u) => ({ userId: u.user_id, fullName: u.full_name, role: u.role }));
    return respondJson(res, 200, users);
  }

  const session = authenticate(sessions, req);
  if (!session) return respondJson(res, 401, { error: 'log in first' });

  if (method === 'POST' && url === '/api/logout') {
    const token = bearer(req);
    if (token) sessions.delete(token);
    return respondJson(res, 200, { ok: true });
  }

  if (method === 'GET' && url === '/api/products') {
    const products = listProducts(db).map((product) => ({
      productId: product.product_id,
      sku: product.sku,
      name: product.name,
      unitPrice: product.unit_price,
      taxRateBp: product.tax_rate_bp,
      lowStockThreshold: product.low_stock_threshold,
      isTaxed: product.tax_rate_bp > 0,
      onHand:
        get<{ quantity: number }>(db, 'SELECT quantity FROM stock_on_hand WHERE product_id = ?', product.product_id)
          ?.quantity ?? 0,
    }));
    return respondJson(res, 200, products);
  }

  if (method === 'GET' && url.startsWith('/api/products/') && url !== '/api/products') {
    const productId = url.slice('/api/products/'.length);
    const product = get<ProductRow>(db, 'SELECT * FROM products WHERE product_id = ?', productId);
    if (!product) return respondJson(res, 404, { error: 'product not found' });
    return respondJson(res, 200, {
      productId: product.product_id,
      sku: product.sku,
      name: product.name,
      unit_price: product.unit_price,
      cost_price: product.cost_price,
      tax_rate_bp: product.tax_rate_bp,
      low_stock_threshold: product.low_stock_threshold,
      is_active: product.is_active,
      version: product.version,
    });
  }

  if (method === 'POST' && url.startsWith('/api/products/') && url !== '/api/products') {
    const productId = url.slice('/api/products/'.length);
    const body = await readJson(req);
    if (body.unitPrice !== undefined && (!Number.isInteger(body.unitPrice as number) || body.unitPrice as number < 0)) {
      return respondJson(res, 400, { error: 'unitPrice must be a non-negative integer in minor units' });
    }
    if (body.costPrice !== undefined && (!Number.isInteger(body.costPrice as number) || body.costPrice as number < 0)) {
      return respondJson(res, 400, { error: 'costPrice must be a non-negative integer in minor units' });
    }
    if (body.taxRateBp !== undefined && (!Number.isInteger(body.taxRateBp as number) || body.taxRateBp as number < 0 || body.taxRateBp as number > 100_000)) {
      return respondJson(res, 400, { error: 'taxRateBp must be between 0 and 10000 basis points' });
    }
    if (body.lowStockThreshold !== undefined && (!Number.isInteger(body.lowStockThreshold as number) || body.lowStockThreshold as number < 0)) {
      return respondJson(res, 400, { error: 'lowStockThreshold must be a non-negative integer' });
    }
    if (body.sku === undefined || !(body.sku as string).trim()) {
      return respondJson(res, 400, { error: 'sku is required' });
    }
    if (body.name === undefined) {
      return respondJson(res, 400, { error: 'name is required' });
    }
    try {
      const existing = get<ProductRow>(db, 'SELECT * FROM products WHERE product_id = ?', productId);
      if (!existing) return respondJson(res, 404, { error: 'product not found' });
      if (body.version !== undefined && body.version !== existing.version) {
        return respondJson(res, 409, { error: 'stale edit: product version is now ' + existing.version + '; please reload and resubmit' });
      }
      const at = new Date().toISOString();
      const nextVersion = (existing.version ?? 0) + 1;
      run(db, `UPDATE products SET sku = ?, name = ?, unit_price = ?, cost_price = ?, tax_rate_bp = ?, low_stock_threshold = ?, version = ?, updated_at = ? WHERE product_id = ?`,
        (body.sku as string).trim(), (body.name as string), body.unitPrice as number, body.costPrice as number, body.taxRateBp as number, body.lowStockThreshold as number, nextVersion, at, productId);
      const row = get<ProductRow>(db, 'SELECT * FROM products WHERE product_id = ?', productId)!;
      return respondJson(res, 200, {
        productId: row.product_id,
        sku: row.sku,
        name: row.name,
        unit_price: row.unit_price,
        cost_price: row.cost_price,
        tax_rate_bp: row.tax_rate_bp,
        low_stock_threshold: row.low_stock_threshold,
        is_active: row.is_active,
        version: row.version,
      });
    } catch (error: any) {
      if (error.message?.includes('stale edit')) return respondJson(res, 409, { error: error.message });
      if (error.message?.includes('unitPrice') || error.message?.includes('costPrice') || error.message?.includes('taxRateBp') || error.message?.includes('lowStockThreshold') || error.message?.includes('sku')) return respondJson(res, 400, { error: error.message });
      throw error;
    }
  }

  if (method === 'GET' && url === '/api/shift') {
    const shift = openShiftForDevice(db, session.deviceId);
    return respondJson(res, 200, {
      shiftId: shift?.shift_id ?? null,
      openedAt: shift?.opened_at ?? null,
      status: shift?.status ?? null,
    });
  }

  if (method === 'POST' && url === '/api/shift') {
    const body = await readJson(req);
    const shift = openShift(db, {
      deviceId: session.deviceId,
      userId: session.userId,
      openingFloat: Number(body.openingFloat ?? 0),
    });
    return respondJson(res, 200, { shiftId: shift.shift_id, openedAt: shift.opened_at });
  }

  if (method === 'GET' && url === '/api/reports/sales') {
    let sql = 'SELECT s.sale_id, s.receipt_number, s.cashier_id, s.currency, s.subtotal, s.discount_total, s.tax_total, s.total, s.created_at, s.status, u.full_name as cashier_name FROM sales s LEFT JOIN users u ON s.cashier_id = u.user_id ORDER BY s.created_at DESC';
    const params: any[] = [];
    const urlObj = new URL(url, 'http://localhost');
    const from = urlObj.searchParams.get('from');
    const to = urlObj.searchParams.get('to');
    const cashier = urlObj.searchParams.get('cashier');
    if (from) { sql += ' WHERE s.created_at >= ?'; params.push(from); }
    if (to) { if (!from) sql += ' WHERE'; sql += ' AND s.created_at <= ?'; params.push(to); }
    if (cashier) { if (!from && !to) sql += ' WHERE'; sql += ' s.cashier_id = ?'; params.push(cashier); }
    const sales = db.prepare(sql).all(...params) as any[];
    const report = sales.map((s) => ({
      saleId: s.sale_id,
      receiptNumber: s.receipt_number,
      date: s.created_at,
      cashierId: s.cashier_id,
      cashierName: s.cashier_name || 'Unknown',
      currency: s.currency,
      subtotal: s.subtotal,
      discountTotal: s.discount_total,
      taxTotal: s.tax_total,
      total: s.total,
      status: s.status,
    }));
    return respondJson(res, 200, report);
  }

  if (method === 'POST' && url === '/api/checkout') {
    const body = await readJson(req);
    const lines = Array.isArray(body.lines) ? body.lines : [];
    const shift = openShiftForDevice(db, session.deviceId);
    let result;
    try {
      result = checkout(db, {
        deviceId: session.deviceId,
        cashierId: session.userId,
        shiftId: shift?.shift_id,
        lines: lines.map((line: Record<string, unknown>) => ({
          productId: String(line.productId ?? ''),
          quantity: Number(line.quantity ?? 0),
          discount: Number(line.discount ?? 0),
        })),
      });
    } catch (error) {
      return handleDomainError(res, error);
    }
    let paymentId: string | null = null;
    let paymentStatus: 'confirmed' | 'pending' | null = null;
    const amount = result.sale.total;
    const paymentMethod = body.paymentMethod as PaymentMethod ?? 'cash';
    if (amount > 0) {
      const payment = createPayment(db, { saleId: result.sale.sale_id, method: paymentMethod, amount });
      // Mobile money is the one ASYNCHRONOUS method: the provider confirms it,
      // not the cashier, so the row stays pending until verifyMobileMoneyPayment
      // sees the provider's verdict. Everything else is settled at the till.
      if (paymentMethod === 'mobile_money') {
        paymentId = payment.payment_id;
        paymentStatus = 'pending';
      } else {
        confirmPayment(db, payment.payment_id);
        paymentId = payment.payment_id;
        paymentStatus = 'confirmed';
      }
    }
    return respondJson(res, 201, {
      paymentStatus,
      receiptNumber: result.receiptNumber,
      saleId: result.sale.sale_id,
      paymentId,
      currency: result.sale.currency,
      subtotal: result.sale.subtotal,
      discountTotal: result.sale.discount_total,
      taxTotal: result.sale.tax_total,
      total: result.sale.total,
      items: result.items.map((item) => ({
        sku: item.sku,
        name: item.name,
        quantity: item.quantity,
        unitPrice: item.unit_price,
        discount: item.discount,
        tax: item.tax,
        lineTotal: item.line_total,
      })),
    });
  }

  // ---- Mobile money ---------------------------------------------------
  // A cashier hands an attempt to the configured provider (idempotently) and
  // polls its status; verification is pull-based here because the provider's
  // push webhook lives on the Kotlin server (HMAC-verified), not on this LAN.

  if (method === 'POST' && url === '/api/payments/mobile-money/initiate') {
    const body = await readJson(req);
    const paymentId = String(body.paymentId ?? '');
    if (paymentId === '') return respondJson(res, 400, { error: 'paymentId is required' });
    if (!momoProvider) return respondJson(res, 503, { error: 'no mobile-money provider is configured on this till' });
    let result;
    try {
      result = await initiateMobileMoneyPayment(db, paymentId, momoProvider);
    } catch (error) {
      return handleMomoDomainError(res, error);
    }
    return respondJson(res, result.initiated ? 201 : 200, {
      ...paymentStatusView(db, result.payment),
      initiated: result.initiated,
      ...(result.instructions ? { instructions: result.instructions } : {}),
    });
  }

  if (method === 'GET' && url.startsWith('/api/payments/') && url !== '/api/payments/mobile-money/initiate') {
    const paymentId = decodeURIComponent(url.slice('/api/payments/'.length));
    let payment = findPaymentRow(db, paymentId);
    if (!payment) return respondJson(res, 404, { error: 'payment not found' });
    // Polling a pending attempt IS the verification hook: the provider's
    // verdict (when one arrived) transitions the row exactly once.
    if (momoProvider && payment.method === 'mobile_money' && payment.status === 'pending') {
      try {
        payment = await verifyMobileMoneyPayment(db, paymentId, momoProvider);
      } catch (error) {
        return handleMomoDomainError(res, error);
      }
    }
    return respondJson(res, 200, paymentStatusView(db, payment));
  }

  respondJson(res, 404, { error: 'not found' });
}

function handleMomoDomainError(res: ServerResponse, error: unknown): void {
  if (error instanceof MomoError) return respondJson(res, 409, { error: error.message });
  if (error instanceof Error) {
    return respondJson(res, /not found/.test(error.message) ? 404 : 409, { error: error.message });
  }
  throw error;
}

function handleDomainError(res: ServerResponse, error: unknown): void {
  if (error instanceof PermissionError) return respondJson(res, 403, { error: error.message });
  if (error instanceof CheckoutError || error instanceof ProductError) {
    return respondJson(res, 400, { error: error.message });
  }
  throw error instanceof Error ? error : new Error(String(error));
}

function authenticate(sessions: Map<string, Session>, req: IncomingMessage): Session | undefined {
  const token = bearer(req);
  return token ? sessions.get(token) : undefined;
}

function bearer(req: IncomingMessage): string | undefined {
  const header = req.headers.authorization;
  if (!header?.startsWith('Bearer ')) return undefined;
  return header.slice(7).trim() || undefined;
}

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8')) as Record<string, unknown>;
  } catch {
    throw new Error('request body is not valid JSON');
  }
}

function respondJson(res: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) });
  res.end(payload);
}

function serveStatic(res: ServerResponse, relative: string): void {
  const file = resolve(STATIC_DIR, relative);
  if (!file.startsWith(resolve(STATIC_DIR))) {
    return respondJson(res, 403, { error: 'forbidden' });
  }
  try {
    const content = readFileSync(file);
    const ext = relative.slice(relative.lastIndexOf('.'));
    const type = MIME[ext] ?? 'application/octet-stream';
    res.writeHead(200, { 'content-type': type, 'content-length': content.length });
    res.end(content);
  } catch {
    respondJson(res, 404, { error: 'not found' });
  }
}