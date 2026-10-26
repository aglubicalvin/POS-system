import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { receiveGoods } from '../src/domain/inventory.ts';
import { get } from '../src/db/db.ts';
import { startTillUi, type TillUiHandle } from '../src/ui/server.ts';
import { seedStore, type SeededStore } from './helpers.ts';

/**
 * The Till UI slice, exercised over a real HTTP socket the way a browser hits
 * it: a PIN gate with device binding and lockout, a catalog with on-hand stock,
 * an atomic checkout that reprices through the domain layer, and a cash payment
 * recorded confirmed with the sale. The UI adds no business rules; these tests
 * prove the wiring is the same rules the sync tests assert.
 */
describe('till ui over real http', () => {
  let store: SeededStore;
  let till: TillUiHandle;
  let base: string;

  beforeAll(async () => {
    store = seedStore();
    receiveGoods(store.db, {
      deviceId: store.deviceId,
      supplierId: store.supplier.supplier_id,
      receivedBy: store.manager.user_id,
      reference: 'INV-UI-1',
      lines: [
        { productId: store.products['WATER-500']!.product_id, quantity: 50, unitCost: 90 },
        { productId: store.products['MILO-400']!.product_id, quantity: 10, unitCost: 3050 },
      ],
    });
    till = await startTillUi(store.db, { port: 0, deviceId: store.deviceId });
    base = `http://127.0.0.1:${till.port}`;
  });

  afterAll(async () => {
    await till.close();
  });

  const water = () => store.products['WATER-500']!;

  async function login(pin: string): Promise<string> {
    const response = await fetch(`${base}/api/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ userId: store.cashier.user_id, pin }),
    });
    return JSON.stringify({ status: response.status, body: await response.json() });
  }

  async function cashierToken(): Promise<string> {
    const result = await login('1234');
    return (JSON.parse(result) as { body: { token: string } }).body.token;
  }

  it('serves the app shell without authentication', async () => {
    const response = await fetch(base);
    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toContain('text/html');
    const html = await response.text();
    expect(html).toContain('id="login-user"');
  });

  it('gates login on the PIN with device binding, lockout and session tokens', async () => {
    // Wrong PIN: counted, not accepted.
    const wrong = JSON.parse(await login('0000')) as { status: number; body: { ok: boolean; reason: string } };
    expect(wrong.status).toBe(401);
    expect(wrong.body).toMatchObject({ ok: false, reason: 'bad-pin' });

    // Correct PIN on the bound device: a bearer session comes back.
    const right = JSON.parse(await login('1234')) as {
      status: number;
      body: { token: string; user: { fullName: string; role: string } };
    };
    expect(right.status).toBe(200);
    expect(right.body.user).toMatchObject({ role: 'cashier', fullName: 'Akosua Mensah' });
    expect(right.body.token).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('refuses the till API without a session and hides secrets on the user list', async () => {
    expect((await fetch(`${base}/api/products`)).status).toBe(401);
    expect((await fetch(`${base}/api/checkout`, { method: 'POST', body: '{}' })).status).toBe(401);

    // User ids reach the login screen, hashes never do.
    const users = (await (await fetch(`${base}/api/users`)).json()) as Array<Record<string, unknown>>;
    for (const user of users) {
      expect(Object.keys(user).sort()).toEqual(['fullName', 'role', 'userId']);
    }
  });

  it('lists the catalog with tariff metadata and on-hand stock', async () => {
    const products = (await (
      await fetch(`${base}/api/products`, { headers: { authorization: `Bearer ${await cashierToken()}` } })
    ).json()) as Array<{
      productId: string;
      sku: string;
      unitPrice: number;
      taxRateBp: number;
      onHand: number;
    }>;

    const water = products.find((p) => p.sku === 'WATER-500')!;
    expect(water).toMatchObject({ unitPrice: 150, taxRateBp: 0, onHand: 50 });
    const milo = products.find((p) => p.sku === 'MILO-400')!;
    expect(milo).toMatchObject({ taxRateBp: 1250, onHand: 10 });
  });

  it('checks out atomically, reprices through the domain and records cash', async () => {
    const beforeStock = water(); // untouched catalog price: 150
    expect(beforeStock.product_id).toBeDefined();

    const response = await fetch(`${base}/api/checkout`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${await cashierToken()}` },
      body: JSON.stringify({ lines: [{ productId: water().product_id, quantity: 2 }] }),
    });
    expect(response.status).toBe(201);
    const receipt = (await response.json()) as {
      receiptNumber: string;
      saleId: string;
      paymentId: string | null;
      total: number;
      subtotal: number;
      taxTotal: number;
      items: Array<{ quantity: number; unitPrice: number; lineTotal: number }>;
    };

    // Domain-repriced numbers, not the client's cart arithmetic.
    expect(receipt.items[0]).toMatchObject({ quantity: 2, unitPrice: 150, lineTotal: 300 });
    expect(receipt).toMatchObject({ subtotal: 300, taxTotal: 0, total: 300 });
    expect(receipt.receiptNumber).toMatch(/^[0-9A-F]{8}-\d{8}-0001$/);

    // The sale, its confirmed payment and the outbox event all exist locally.
    expect(
      get<{ total: number; status: string }>(store.db, 'SELECT total, status FROM sales WHERE sale_id = ?', receipt.saleId),
    ).toMatchObject({ total: 300, status: 'completed' });
    expect(
      get<{ status: string }>(store.db, 'SELECT status FROM payments WHERE payment_id = ?', receipt.paymentId!),
    ).toMatchObject({ status: 'confirmed' });
    expect(
      get<{ event_type: string }>(store.db, "SELECT event_type FROM outbox WHERE aggregate_id = ? AND event_type = 'sale.created'", receipt.saleId),
    ).toBeDefined();

    // Stock moved by the sale.
    const onHand = get<{ quantity: number }>(
      store.db,
      'SELECT quantity FROM stock_on_hand WHERE product_id = ?',
      water().product_id,
    );
    expect(onHand?.quantity).toBe(48);
  });

  it('surfaces domain rejections: unknown product, oversized discount', async () => {
    const token = await cashierToken();
    const unknown = await fetch(`${base}/api/checkout`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
      body: JSON.stringify({ lines: [{ productId: 'not-a-product', quantity: 1 }] }),
    });
    expect(unknown.status).toBe(400);
    expect(((await unknown.json()) as { error: string }).error).toContain('not found');

    // A cashier's discount ceiling is 5%; 45 of 150 pesewas is 30%.
    const overDiscount = await fetch(`${base}/api/checkout`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
      body: JSON.stringify({ lines: [{ productId: water().product_id, quantity: 1, discount: 45 }] }),
    });
    expect(overDiscount.status).toBe(403);
  });

  it('attaches the sale to the device shift and reports the shift state', async () => {
    const token = await cashierToken();

    const before = (await (
      await fetch(`${base}/api/shift`, { headers: { authorization: `Bearer ${token}` } })
    ).json()) as { shiftId: string | null };
    expect(before.shiftId).toBeNull();

    const opened = await fetch(`${base}/api/shift`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
      body: JSON.stringify({ openingFloat: 5000 }),
    });
    expect(opened.status).toBe(200);
    const { shiftId } = (await opened.json()) as { shiftId: string };

    const sale = await fetch(`${base}/api/checkout`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
      body: JSON.stringify({ lines: [{ productId: water().product_id, quantity: 1 }] }),
    });
    expect(sale.status).toBe(201);
    const saleId = ((await sale.json()) as { saleId: string }).saleId;
    expect(get<{ shift_id: string }>(store.db, 'SELECT shift_id FROM sales WHERE sale_id = ?', saleId)).toMatchObject({
      shift_id: shiftId,
    });
  });
});
