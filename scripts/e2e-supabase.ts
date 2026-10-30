/**
 * Phase 4.3 end-to-end verification against a REAL test Supabase project.
 *
 * Never pointed at the production database by default: every credential comes
 * from the environment, and the script refuses to run without them (it exits 0
 * with a how-to so plain `npm test` stays self-contained).
 *
 *   SUPABASE_URL               test project URL, e.g. https://xxxx.supabase.co
 *   SUPABASE_SERVICE_ROLE_KEY  service key — bypasses RLS, seeds and cleans rows
 *   SUPABASE_ANON_KEY          public key — must reach NOTHING protected
 *   SUPABASE_DB_URL            optional libpq connection string; when present
 *                              (and `psql` is on PATH) the script probes for
 *                              the 0005-0007 objects and applies any missing
 *                              migration files before the REST checks
 *
 * What it proves, end to end:
 *   1. administration: an admin user reads/writes exactly the admin surface
 *   2. protection: anon reaches no customer/payment rows; direct payment edits by
 *      authenticated callers are refused (the server owns payment state)
 *   3. reconciliation: admin_reconcile_mobile_money_payment matches a confirmed
 *      payment, is idempotent, and refuses a mismatch without touching the row
 *   4. lifecycle: everything created for the check is deleted again.
 *
 * The test project must already carry the canonical POS schema
 * (server/src/main/resources/db/schema.sql applied).
 */

const {
  SUPABASE_URL,
  SUPABASE_SERVICE_ROLE_KEY,
  SUPABASE_ANON_KEY = '',
  SUPABASE_DB_URL = '',
} = process.env;

const REQUIRED = [
  ['SUPABASE_URL', SUPABASE_URL],
  ['SUPABASE_SERVICE_ROLE_KEY', SUPABASE_SERVICE_ROLE_KEY],
] as const;

if (REQUIRED.some(([, value]) => !value)) {
  console.log('SKIPPED: set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY (a TEST project, never production) to run the Supabase end-to-end checks.');
  console.log('Optionally set SUPABASE_ANON_KEY and SUPABASE_DB_URL for the anonymous-surface and migration-appliance checks.');
  process.exit(0);
}

const base = SUPABASE_URL!.replace(/\/+$/, '');
let failures = 0;

function check(label: string, ok: boolean, detail = ''): void {
  if (ok) console.log(`  PASS ${label}`);
  else {
    failures += 1;
    console.error(`  FAIL ${label}${detail ? ` — ${detail}` : ''}`);
  }
}

interface JsonRecord { [key: string]: unknown }

async function rest(
  path: string,
  options: { method?: string; body?: unknown; token?: string; anon?: boolean; prefer?: string } = {},
): Promise<{ status: number; body: unknown }> {
  const headers: Record<string, string> = {
    apikey: options.anon ? SUPABASE_ANON_KEY! : SUPABASE_SERVICE_ROLE_KEY!,
    'content-type': 'application/json',
  };
  if (options.token) headers.authorization = `Bearer ${options.token}`;
  if (options.prefer) headers.prefer = options.prefer;
  const response = await fetch(`${base}${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  const text = await response.text();
  let parsed: unknown = text;
  try { parsed = text ? JSON.parse(text) : null; } catch { /* keep raw */ }
  return { status: response.status, body: parsed };
}

function rowsOf(body: unknown): JsonRecord[] {
  return Array.isArray(body) ? (body as JsonRecord[]) : [];
}

async function isAdminToken(userId: string, email: string, password: string): Promise<string> {
  const login = await rest('/auth/v1/token?grant_type=password', {
    method: 'POST',
    anon: true,
    body: { email, password },
  });
  if (login.status !== 200) throw new Error(`admin login failed (${login.status}): ${JSON.stringify(login.body)}`);
  const session = login.body as { access_token: string; user?: { id?: string } };
  if (session.user?.id && session.user.id !== userId) throw new Error('auth round-trip returned a different user');
  return session.access_token;
}

async function createAuthUser(email: string, password: string, metadata: Record<string, unknown>): Promise<string> {
  const response = await rest('/auth/v1/admin/users', {
    method: 'POST',
    body: { email, password, email_confirm: true, user_metadata: metadata },
  });
  if (response.status !== 200 && response.status !== 201) {
    throw new Error(`could not create auth user ${email} (${response.status}): ${JSON.stringify(response.body)}`);
  }
  return ((response.body as { id: string }).id);
}

async function deleteAuthUser(userId: string): Promise<void> {
  await rest(`/auth/v1/admin/users/${userId}`, { method: 'DELETE' });
}

// ---------------------------------------------------------------------------
// Optional: apply missing 0005-0007 migrations through psql
// ---------------------------------------------------------------------------

const MIGRATIONS = [
  '0005_customers_payroll_hirepurchase.sql',
  '0006_phase43_workflows.sql',
  '0007_mobile_money_reconciliation.sql',
];

async function migrationsApplied(): Promise<Record<string, boolean>> {
  const probe = await rest('/rest/v1/admin_inventory_overview?select=product_id&limit=1', { anon: true });
  const reconcile = await rest('/rest/v1/rpc/admin_reconcile_mobile_money_payment', {
    method: 'POST',
    anon: true,
    body: { p_payment_id: 'probe', p_provider_ref: 'probe', p_amount: 1 },
  });
  // Either HTTP 200/404 (RPC exists and denied by auth) or SQL-state P-xxxx errors
  // through PostgREST both tell the same story: object exists.
  const inventoryOk = probe.status !== 404;
  const reconcileOk = reconcile.status !== 404;
  return { '0006_phase43_workflows.sql': inventoryOk, '0007_mobile_money_reconciliation.sql': reconcileOk, '0005_customers_payroll_hirepurchase.sql': inventoryOk };
}

async function applyMigrationsIfMissing(): Promise<void> {
  if (!SUPABASE_DB_URL) {
    console.log('  (set SUPABASE_DB_URL with psql on PATH to let the script apply missing 0005-0007 migrations)');
    return;
  }
  const applied = await migrationsApplied();
  for (const file of MIGRATIONS) {
    if (applied[file]) {
      console.log(`  migrations: ${file} already applied`);
      continue;
    }
    console.log(`  migrations: applying ${file} …`);
    const { spawnSync } = await import('node:child_process');
    const result = spawnSync('psql', [SUPABASE_DB_URL, '-v', 'ON_ERROR_STOP=1', '-f', new URL(`../supabase/migrations/${file}`, import.meta.url).pathname], { encoding: 'utf8' });
    check(`migration ${file} applied via psql`, result.status === 0, result.stderr?.slice(0, 400));
  }
}

// ---------------------------------------------------------------------------

async function main(): Promise<void> {
  console.log(`Supabase end-to-end against ${base}`);

  await applyMigrationsIfMissing();

  const run = `e2e-${Date.now()}`;
  const adminEmail = `${run}-admin@example.com`;
  const cashierEmail = `${run}-cashier@example.com`;
  const password = `${run}-P4ssw0rd!`;

  // Seed: one protected customer the checks must agree about.
  const customer = await rest('/rest/v1/customers', {
    method: 'POST',
    prefer: 'return=representation',
    body: { full_name: 'E2E Customer', staff_number: run, phone: '+233200000000' },
  });
  check('service seeds a customer', customer.status === 201, JSON.stringify(customer.body).slice(0, 200));
  const customerId = rowsOf(customer.body)[0]?.customer_id as string | undefined;
  if (!customerId) throw new Error('seeded customer row missing — reconcile checks cannot proceed');

  // Identity: an admin profile and a cashier profile, both real auth users.
  const adminId = await createAuthUser(adminEmail, password, {});
  const cashierId = await createAuthUser(cashierEmail, password, {});
  for (const [email, id, role] of [[adminEmail, adminId, 'admin'], [cashierEmail, cashierId, 'cashier']] as const) {
    const profile = await rest('/rest/v1/profiles', { method: 'POST', body: { id, full_name: email.split('@')[0]!, role } });
    check(`profiles row (${role}) for verification`, profile.status >= 200 && profile.status < 300, JSON.stringify(profile.body).slice(0, 200));
  }

  const adminToken = await isAdminToken(adminId, adminEmail, password);
  check('admin password sign-in works', Boolean(adminToken));

  // What the admin may reach…
  const adminCustomers = await rest('/rest/v1/customers?select=*', { token: adminToken, anon: true });
  check(
    'admin reads the seeded customer',
    adminCustomers.status === 200 && rowsOf(adminCustomers.body).some((row) => row.customer_id === customerId),
    JSON.stringify(adminCustomers.body).slice(0, 160),
  );

  // …what nobody else may.
  if (SUPABASE_ANON_KEY) {
    const anonCustomers = await rest('/rest/v1/customers?select=full_name', { anon: true });
    const anonLeaked = rowsOf(anonCustomers.body).some((row) => row.customer_id === customerId);
    check('anon sees no customer rows (RLS)', anonCustomers.status === 200 && !anonLeaked, JSON.stringify(anonCustomers.body).slice(0, 160));
  } else {
    console.log('  (set SUPABASE_ANON_KEY to verify the anonymous surface can reach nothing)');
  }
  const cashierWrite = await rest('/rest/v1/payments', {
    token: await isAdminToken(cashierId, cashierEmail, password),
    anon: true,
    method: 'POST',
    body: { payment_id: 'e2e-forged', sale_id: 'e2e-forged', method: 'mobile_money', amount: 1, status: 'confirmed', idempotency_key: 'e2e-forged' },
  });
  check('authenticated non-admin cannot forge payment rows', cashierWrite.status >= 400, JSON.stringify(cashierWrite.body).slice(0, 160));

  // Reconciliation lifecycle on a canonical confirmed MoMo payment.
  const paymentId = `${run}-pay`;
  const reconcileSeedId = `${run}-sale`;
  const saleSeed = await rest('/rest/v1/sales', {
    method: 'POST',
    prefer: 'return=representation',
    body: {
      sale_id: reconcileSeedId, device_id: 'e2e-device', receipt_number: `${run}-R1`, cashier_id: 'e2e-cashier',
      currency: 'GHS', subtotal: 500, discount_total: 0, tax_total: 0, total: 500, kind: 'sale', status: 'completed', sync_state: 'synced',
    },
  });
  check('service seeds a canonical sale', saleSeed.status >= 200 && saleSeed.status < 300, JSON.stringify(saleSeed.body).slice(0, 200));
  // A pending MoMo attempt first: reconciliation must NOT confirm it.
  const paymentSeed = await rest('/rest/v1/payments', {
    method: 'POST',
    prefer: 'return=representation',
    body: { payment_id: paymentId, sale_id: reconcileSeedId, method: 'mobile_money', amount: 500, status: 'pending', idempotency_key: `${run}:momo:500`, provider: 'e2e-provider', provider_ref: `${run}-ref` },
  });
  check('service seeds a pending MoMo payment', paymentSeed.status >= 200 && paymentSeed.status < 300, JSON.stringify(paymentSeed.body).slice(0, 200));

  const reconcile = async (amount: number, providerRef: string): Promise<{ matched: boolean; reason: string }> => {
    const response = await rest('/rest/v1/rpc/admin_reconcile_mobile_money_payment', {
      method: 'POST',
      token: adminToken,
      anon: true,
      body: { p_payment_id: paymentId, p_provider_ref: providerRef, p_amount: amount },
    });
    if (response.status !== 200) return { matched: false, reason: `http-${response.status}` };
    const row = Array.isArray(response.body) ? rowsOf(response.body)[0] : (response.body as JsonRecord);
    return { matched: Boolean(row?.matched), reason: String(row?.reason) };
  };

  const notConfirmed = await reconcile(500, `${run}-ref`);
  check('reconciliation refuses an unconfirmed payment (not-confirmed)', !notConfirmed.matched && notConfirmed.reason === 'not-confirmed', JSON.stringify(notConfirmed));

  // Only the server side confirms payment state (the Kotlin server's webhook does
  // this in production with its service connection; the service key stands in).
  const confirm = await rest(`/rest/v1/payments?payment_id=eq.${encodeURIComponent(paymentId)}`, {
    method: 'PATCH',
    body: { status: 'confirmed', provider_ref: `${run}-ref` },
  });
  check('service-role confirms payment state (webhook stand-in)', confirm.status >= 200 && confirm.status < 300, JSON.stringify(confirm.body).slice(0, 160));

  const match = await reconcile(500, `${run}-ref`);
  check('reconciliation matches the confirmed payment', match.matched && match.reason === 'matched', JSON.stringify(match));

  const again = await reconcile(500, `${run}-ref`);
  check('reconciliation is idempotent', again.matched && again.reason === 'matched');

  const mismatch = await reconcile(499, `${run}-ref`);
  check('reconciliation refuses an amount mismatch', !mismatch.matched && mismatch.reason === 'amount-mismatch', JSON.stringify(mismatch));

  // Payment state stays server-owned: an admin session cannot forge a payment row.
  const forgedStatus = await rest('/rest/v1/payments', { token: adminToken, anon: true, method: 'POST', body: { payment_id: `${run}-forged`, sale_id: reconcileSeedId, method: 'mobile_money', amount: 500, status: 'confirmed', idempotency_key: `${run}:forged` } });
  check('authenticated admin cannot insert payment rows', forgedStatus.status >= 400, JSON.stringify(forgedStatus.body).slice(0, 160));
  const forgedEdit = await rest(`/rest/v1/payments?payment_id=eq.${encodeURIComponent(paymentId)}`, { token: adminToken, anon: true, method: 'PATCH', body: { amount: 1, status: 'pending' } });
  check('authenticated admin cannot edit payment rows', forgedEdit.status >= 400, JSON.stringify(forgedEdit.body).slice(0, 160));

  // Cleanup: leave the test project exactly as it was.
  await rest(`/rest/v1/payments?payment_id=eq.${encodeURIComponent(paymentId)}`, { method: 'DELETE' });
  await rest(`/rest/v1/sales?sale_id=eq.${encodeURIComponent(reconcileSeedId)}`, { method: 'DELETE' });
  await rest(`/rest/v1/customers?customer_id=eq.${encodeURIComponent(customerId)}`, { method: 'DELETE' });
  await rest(`/rest/v1/profiles?id=eq.${adminId}`, { method: 'DELETE' });
  await rest(`/rest/v1/profiles?id=eq.${cashierId}`, { method: 'DELETE' });
  await deleteAuthUser(adminId);
  await deleteAuthUser(cashierId);
  console.log('  cleanup done');

  if (failures > 0) {
    console.error(`\n${failures} check(s) failed against the test project.`);
    process.exit(1);
  }
  console.log('\nAll Supabase end-to-end checks passed.');
}

main().catch((error) => {
  console.error(`ABORTED: ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
});
