import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { receiveGoods } from '../src/domain/inventory.ts';
import { all, get } from '../src/db/db.ts';
import {
  initiateMobileMoneyPayment,
  verifyMobileMoneyPayment,
  type MomoInitiationResult,
  type MomoInitiationRequest,
  type MomoProvider,
  type MomoVerification,
} from '../src/domain/momo.ts';
import { startTillUi, type TillUiHandle } from '../src/ui/server.ts';
import { run } from '../src/db/db.ts';
import { seedStore, type SeededStore } from './helpers.ts';
import { verifyChain } from '../src/domain/audit.ts';

/**
 * The mobile-money flow over the REAL till HTTP surface: a MoMo checkout must
 * stay pending, initiation must be refused without a provider, an initiated
 * attempt must be idempotent and audited, and the provider's verdict — never a
 * cashier's keystroke — is what confirms or fails the payment, exactly once.
 */
describe('till mobile-money flow over real http', () => {
  let store: SeededStore;
  let till: TillUiHandle;
  let bare: TillUiHandle; // same store, but no MoMo provider configured
  let base: string;
  let bareBase: string;
  const fake = new FakeMomoProvider();

  beforeAll(async () => {
    store = seedStore();
    receiveGoods(store.db, {
      deviceId: store.deviceId,
      supplierId: store.supplier.supplier_id,
      receivedBy: store.manager.user_id,
      reference: 'INV-MOMO-1',
      lines: [{ productId: store.products['WATER-500']!.product_id, quantity: 50, unitCost: 90 }],
    });
    till = await startTillUi(store.db, { port: 0, deviceId: store.deviceId, momoProvider: fake });
    bare = await startTillUi(store.db, { port: 0, deviceId: store.deviceId });
    base = `http://127.0.0.1:${till.port}`;
    bareBase = `http://127.0.0.1:${bare.port}`;
  });

  afterAll(async () => {
    await till.close();
    await bare.close();
  });

  beforeEach(() => fake.clearVerdicts());

  const water = () => store.products['WATER-500']!;

  async function token(): Promise<string> {
    const response = await fetch(`${base}/api/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ userId: store.cashier.user_id, pin: '1234' }),
    });
    return ((await response.json()) as { token: string }).token;
  }

  const auth = (tokenValue: string) => ({ 'content-type': 'application/json', authorization: `Bearer ${tokenValue}` });

  async function momoCheckout(tokenValue: string): Promise<{ saleId: string; paymentId: string }> {
    const response = await fetch(`${base}/api/checkout`, {
      method: 'POST',
      headers: auth(tokenValue),
      body: JSON.stringify({ lines: [{ productId: water().product_id, quantity: 1 }], paymentMethod: 'mobile_money' }),
    });
    expect(response.status).toBe(201);
    const receipt = (await response.json()) as { saleId: string; paymentId: string; paymentStatus: string };
    expect(receipt.paymentStatus).toBe('pending');
    return receipt;
  }

  async function initiate(tokenValue: string, paymentId: string): Promise<Response> {
    return fetch(`${base}/api/payments/mobile-money/initiate`, {
      method: 'POST',
      headers: auth(tokenValue),
      body: JSON.stringify({ paymentId }),
    });
  }

  const statusUrl = (paymentId: string) => `${base}/api/payments/${encodeURIComponent(paymentId)}`;

  it('keeps a mobile-money checkout pending and unconfirmed', async () => {
    const tokenValue = await token();
    const receipt = await momoCheckout(tokenValue);

    const row = get<{ status: string; provider: string | null; provider_ref: string | null }>(
      store.db,
      'SELECT status, provider, provider_ref FROM payments WHERE payment_id = ?',
      receipt.paymentId,
    );
    expect(row).toMatchObject({ status: 'pending', provider: null, provider_ref: null });

    // The sale itself is complete and stock has moved — only the money is unresolved.
    expect(get<{ status: string }>(store.db, 'SELECT status FROM sales WHERE sale_id = ?', receipt.saleId)).toMatchObject({ status: 'completed' });
    expect(get<{ quantity: number }>(store.db, 'SELECT quantity FROM stock_on_hand WHERE product_id = ?', water().product_id)).toMatchObject({ quantity: 49 });

    // No payment event may have left the till while the attempt is pending.
    expect(paymentEventsFor(receipt.paymentId)).toHaveLength(0);
  });

  it('fails closed without a configured provider and leaves the attempt untouched', async () => {
    const tokenValue = await token();
    const receipt = await momoCheckout(tokenValue);

    // The provider-less till answers loudly instead of pretending to initiate.
    const response = await fetch(`${bareBase}/api/payments/mobile-money/initiate`, {
      method: 'POST',
      headers: auth(await bareToken()),
      body: JSON.stringify({ paymentId: receipt.paymentId }),
    });
    expect(response.status).toBe(503);
    expect(((await response.json()) as { error: string }).error).toContain('no mobile-money provider');

    const row = get<{ status: string; provider: string | null; provider_ref: string | null }>(
      store.db,
      'SELECT status, provider, provider_ref FROM payments WHERE payment_id = ?',
      receipt.paymentId,
    );
    expect(row).toMatchObject({ status: 'pending', provider: null, provider_ref: null });
  });

  it('initiates idempotently once and audits the attempt', async () => {
    const tokenValue = await token();
    const receipt = await momoCheckout(tokenValue);

    const first = await initiate(tokenValue, receipt.paymentId);
    expect(first.status).toBe(201);
    const body = (await first.json()) as { initiated: boolean; provider: string; providerRef: string; instructions?: string };
    expect(body).toMatchObject({ initiated: true, provider: 'fake-momo', providerRef: fake.refFor(receipt.paymentId) });
    expect(body.instructions).toContain('Approve');

    // A retry recognises the already-initiated attempt instead of re-sending it:
    // the provider must not be asked twice for one attempt.
    const before = fake.initiatedFor(receipt.paymentId);
    const second = await initiate(tokenValue, receipt.paymentId);
    expect(second.status).toBe(200);
    expect(((await second.json()) as { initiated: boolean }).initiated).toBe(false);
    expect(fake.initiatedFor(receipt.paymentId)).toBe(before);

    // The till's audit chain saw the handoff to the provider.
    const audits = all<{ action: string }>(store.db, "SELECT action FROM audit_log WHERE entity_type = 'payment'");
    expect(audits.some((a) => a.action === 'payment.momo_initiated')).toBe(true);
  });

  it('confirms only through the provider verdict, exactly once', async () => {
    const tokenValue = await token();
    const receipt = await momoCheckout(tokenValue);
    await initiate(tokenValue, receipt.paymentId);

    fake.queueVerdicts({ status: 'confirmed' });
    const poll = await fetch(statusUrl(receipt.paymentId), { headers: auth(tokenValue) });
    expect(poll.status).toBe(200);
    expect(await poll.json()).toMatchObject({ status: 'confirmed', providerRef: fake.refFor(receipt.paymentId) });
    expect(get<{ status: string; provider_ref: string }>(store.db, 'SELECT status, provider_ref FROM payments WHERE payment_id = ?', receipt.paymentId)).toMatchObject({
      status: 'confirmed',
      provider_ref: fake.refFor(receipt.paymentId),
    });

    // Exactly one confirmed event left the till for this attempt…
    const events = paymentEventsFor(receipt.paymentId);
    expect(events).toHaveLength(1);
    expect(events[0]!.payload.status).toBe('confirmed');
    // …and polling a banked attempt again neither re-verifies nor adds an event:
    // the verdict stays in the queue because the provider was never asked again.
    fake.queueVerdicts({ status: 'confirmed' });
    const verifiedBefore = fake.verifiedFor(receipt.paymentId);
    const second = await fetch(statusUrl(receipt.paymentId), { headers: auth(tokenValue) });
    expect(((await second.json()) as { status: string }).status).toBe('confirmed');
    expect(fake.verifiedFor(receipt.paymentId)).toBe(verifiedBefore);
    expect(paymentEventsFor(receipt.paymentId)).toHaveLength(events.length);
  });

  it('surfaces a provider failure with its reason and never confirms', async () => {
    const tokenValue = await token();
    const receipt = await momoCheckout(tokenValue);
    await initiate(tokenValue, receipt.paymentId);

    fake.queueVerdicts({ status: 'failed', failureReason: 'insufficient funds on wallet' });
    const poll = await fetch(statusUrl(receipt.paymentId), { headers: auth(tokenValue) });
    const body = (await poll.json()) as { status: string; failureReason: string | null; providerRef: string | null };
    expect(body).toMatchObject({ status: 'failed', failureReason: 'insufficient funds on wallet' });

    const row = get<{ status: string; failure_reason: string | null; provider_ref: string | null }>(
      store.db,
      'SELECT status, failure_reason, provider_ref FROM payments WHERE payment_id = ?',
      receipt.paymentId,
    );
    expect(row).toMatchObject({ status: 'failed', failure_reason: 'insufficient funds on wallet', provider_ref: fake.refFor(receipt.paymentId) });

    // The failed verdict went home as a payment event too.
    const events = paymentEventsFor(receipt.paymentId);
    expect(events).toHaveLength(1);
    expect(events[0]!.payload.status).toBe('failed');
  });

  it('keeps the audit chain intact — and detectable-tamper — across the momo lifecycle', async () => {
    const tokenValue = await token();

    // Drive a full lifecycle for one attempt: initiate then confirm at the poll.
    const confirmed = await momoCheckout(tokenValue);
    await initiate(tokenValue, confirmed.paymentId);
    fake.queueVerdicts({ status: 'confirmed' });
    await fetch(statusUrl(confirmed.paymentId), { headers: auth(tokenValue) });

    // …and a failed one, so both verdict audit entries exist.
    const failed = await momoCheckout(tokenValue);
    await initiate(tokenValue, failed.paymentId);
    fake.queueVerdicts({ status: 'failed', failureReason: 'wallet timed out' });
    await fetch(statusUrl(failed.paymentId), { headers: auth(tokenValue) });

    // Every money transition the provider caused is in the audit chain, in order.
    const actions = all<{ action: string; entity_id: string | null }>(
      store.db,
      "SELECT action, entity_id FROM audit_log WHERE device_id = ? AND entity_type = 'payment' ORDER BY seq",
      store.deviceId,
    );
    expect(actions.filter((a) => a.entity_id === confirmed.paymentId).map((a) => a.action)).toEqual([
      'payment.momo_initiated',
      'payment.confirmed',
    ]);
    expect(actions.filter((a) => a.entity_id === failed.paymentId).map((a) => a.action)).toEqual([
      'payment.momo_initiated',
      'payment.failed',
    ]);

    // The chain itself is intact — each row covers its content and its predecessor.
    expect(verifyChain(store.db, store.deviceId)).toMatchObject({ ok: true });

    // Tamper-evidence: silently editing a historical entry breaks the chain.
    run(store.db, "UPDATE audit_log SET details = '{\"amount\":1}' WHERE entity_id = ? AND action = 'payment.confirmed'", confirmed.paymentId);
    const verdict = verifyChain(store.db, store.deviceId);
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toContain('hash mismatch');
  });

  it('refuses initiation of anything that is not a pending mobile-money attempt', async () => {
    const tokenValue = await token();
    // A cash sale is confirmed at the till by design; it is not MoMo's to confirm.
    const cash = await fetch(`${base}/api/checkout`, {
      method: 'POST',
      headers: auth(tokenValue),
      body: JSON.stringify({ lines: [{ productId: water().product_id, quantity: 1 }], paymentMethod: 'cash' }),
    });
    expect(cash.status).toBe(201);
    const cashReceipt = (await cash.json()) as { paymentId: string; paymentStatus: string };
    expect(cashReceipt.paymentStatus).toBe('confirmed');

    const wrongMethod = await initiate(tokenValue, cashReceipt.paymentId);
    expect(wrongMethod.status).toBe(409);
    expect(((await wrongMethod.json()) as { error: string }).error).toContain('only mobile_money');

    const missing = await fetch(statusUrl('no-such-payment'), { headers: auth(tokenValue) });
    expect(missing.status).toBe(404);

    await expect(() =>
      verifyMobileMoneyPayment(store.db, 'no-such-payment', fake),
    ).rejects.toMatchObject({ name: 'MomoError' });
    await expect(() =>
      initiateMobileMoneyPayment(store.db, 'no-such-payment', fake),
    ).rejects.toMatchObject({ name: 'MomoError' });
  });

  it('gates initiation and status behind the till session', async () => {
    const tokenValue = await token();
    const receipt = await momoCheckout(tokenValue);
    expect((await fetch(`${base}/api/payments/mobile-money/initiate`, { method: 'POST', body: '{}' })).status).toBe(401);
    expect((await fetch(statusUrl(receipt.paymentId))).status).toBe(401);
  });

  /** `payment.updated` events the till generated for one attempt, latest first, payloads parsed. */
  function paymentEventsFor(paymentId: string): Array<{ eventType: string; payload: { status?: string; providerRef?: string | null } }> {
    return all<{ event_type: string; payload: string }>(
      store.db,
      "SELECT event_type, payload FROM outbox WHERE aggregate_id = ? AND event_type = 'payment.updated' ORDER BY created_at",
      paymentId,
    ).map((row) => ({ eventType: row.event_type, payload: JSON.parse(row.payload) as { status?: string; providerRef?: string | null } }));
  }

  async function bareToken(): Promise<string> {
    const response = await fetch(`${bareBase}/api/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ userId: store.cashier.user_id, pin: '1234' }),
    });
    return ((await response.json()) as { token: string }).token;
  }
});

/** Provider fake: deterministic, counted, verdict queue controlled per test. */
class FakeMomoProvider implements MomoProvider {
  readonly name = 'fake-momo';
  private readonly initiatedIds: string[] = [];
  private readonly verifiedIds: string[] = [];
  private verdicts: MomoVerification[] = [];

  initiatedFor(paymentId: string): number {
    return this.initiatedIds.filter((id) => id === paymentId).length;
  }

  verifiedFor(paymentId: string): number {
    return this.verifiedIds.filter((id) => id === paymentId).length;
  }

  queueVerdicts(...values: MomoVerification[]): void {
    this.verdicts.push(...values);
  }

  clearVerdicts(): void {
    this.verdicts = [];
  }

  refFor(paymentId: string): string {
    return `FAKE-${paymentId.slice(0, 8).toUpperCase()}`;
  }

  async initiate(request: MomoInitiationRequest): Promise<MomoInitiationResult> {
    this.initiatedIds.push(request.paymentId);
    return { provider: this.name, providerRef: this.refFor(request.paymentId), instructions: 'Approve on your phone' };
  }

  async verify(request: { paymentId: string; providerRef: string }): Promise<MomoVerification> {
    this.verifiedIds.push(request.paymentId);
    return this.verdicts.shift() ?? { status: 'pending' };
  }
}
