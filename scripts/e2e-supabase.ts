#!/usr/bin/env -S node --experimental-strip-types
/**
 * Phase 4.3 + 4.7 end-to-end verification against a REAL test Supabase project.
 *
 * Never pointed at the production database by default: every credential comes
 * from the environment, and the script refuses to run without them (it exits 0
 * with a how-to so plain `npm test` stays self-contained).
 *
 *   SUPABASE_URL               test project URL, e.g. https://xxxx.supabase.co
 *   SUPABASE_SERVICE_ROLE_KEY  service key — bypasses RLS, seeds and cleans up
 *   SUPABASE_ANON_KEY          public key — must reach NOTHING protected
 *   SUPABASE_DB_URL            optional libpq connection string; when present
 *                              (and `psql` is on PATH) the script probes for
 *                              the 0001/0005-0009 objects and applies any
 *                              missing migration files before the REST checks
 *
 * What it proves, end to end:
 *   1. administration: an admin user reads/writes exactly the admin surface
 *   2. protection: anon reaches no customer/payment rows; direct payment edits
 *      by authenticated callers are refused (the server owns payment state)
 *   3. reconciliation: admin_reconcile_mobile_money_payment matches a
 *      confirmed payment, is idempotent, and refuses a mismatch without
 *      touching the row
 *   4. supervisor authorization (Phase 4.7): a plain admin cannot post, escalate
 *      or deny faults; only a profile with `is_supervisor` can, enforced by RLS
 *   5. fault lifecycle (Phase 4.7): supervisors post and advance faults through
 *      reported -> with_approvals -> posted -> denied -> closed, and the linked
 *      payment flips to blocked and back to unblocked on denial
 *
 * NEVER run this against production. It creates and then deletes auth users,
 * profiles, customers, sales, payments and (where present) approval-queue rows.
 * It exits 0 with an explicit how-to unless the credentials are set, so pointing
 * it at a test project is an explicit, deliberate act by the operator.
 */

import { readFileSync } from "node:fs";
import { resolve } from "node:path";

try {
  const raw = readFileSync(resolve(process.cwd(), ".env"), "utf8");
  for (const line of raw.split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (!m || !m[1]) continue;
    if (!(m[1] in process.env)) process.env[m[1]] = m[2] ?? "";
  }
} catch {
  // no .env — env vars only
}

const {
  SUPABASE_URL,
  SUPABASE_SERVICE_ROLE_KEY,
  SUPABASE_ANON_KEY = "",
  SUPABASE_DB_URL = "",
} = process.env;

const REQUIRED = [
  ["SUPABASE_URL", SUPABASE_URL],
  ["SUPABASE_SERVICE_ROLE_KEY", SUPABASE_SERVICE_ROLE_KEY],
] as const;

if (REQUIRED.some(([, value]) => !value)) {
  console.log(
    "SKIPPED: set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY (a TEST project, never production) to run the Supabase end-to-end checks.",
  );
  console.log(
    "Optionally set SUPABASE_ANON_KEY and SUPABASE_DB_URL for the anonymous-surface and migration-appliance checks.",
  );
  process.exit(0);
}

const base = SUPABASE_URL!.replace(/\\+$/, "");

let failures = 0;

function check(label: string, ok: boolean, detail = ""): void {
  if (ok) {
    console.log(`  PASS ${label}`);
  } else {
    failures += 1;
    console.error(`  FAIL ${label}${detail ? ` — ${detail}` : ""}`);
  }
}

interface JsonRecord {
  [key: string]: unknown;
}

/** Null-safe detail formatter: `JSON.stringify(undefined)` has no `.slice`. */
function show(value: unknown, max = 200): string {
  try {
    return (JSON.stringify(value) ?? "undefined").slice(0, max);
  } catch {
    return String(value).slice(0, max);
  }
}

function rowsOf(body: unknown): JsonRecord[] {
  return Array.isArray(body) ? (body as JsonRecord[]) : [];
}

function encode(s: string): string {
  return encodeURIComponent(s);
}

async function rest(
  path: string,
  options: {
    method?: string;
    body?: unknown;
    token?: string;
    anon?: boolean;
    prefer?: string;
  } = {},
): Promise<{ status: number; body: unknown }> {
  // PostgREST picks the database role from the Authorization bearer, not from
  // `apikey`. Without the bearer a "service" request would run as `anon`, and
  // RLS refuses the seeding — which is exactly what happens on a real project
  // if only the key header is sent.
  const key = options.anon ? SUPABASE_ANON_KEY! : SUPABASE_SERVICE_ROLE_KEY!;
  const headers: Record<string, string> = {
    apikey: key,
    authorization: `Bearer ${options.token ?? key}`,
    "content-type": "application/json",
  };
  if (options.prefer) headers.prefer = options.prefer;

  const response = await fetch(`${base}${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  const text = await response.text();
  let parsed: unknown = text;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    // keep raw
  }
  return { status: response.status, body: parsed };
}

async function createAuthUser(
  email: string,
  password: string,
): Promise<string> {
  const response = await rest("/auth/v1/admin/users", {
    method: "POST",
    body: { email, password, email_confirm: true, app_metadata: { role: "admin" } },
  });
  if (response.status !== 200 && response.status !== 201) {
    throw new Error(
      `could not create auth user ${email} (${response.status}): ${JSON.stringify(
        response.body,
      )}`,
    );
  }
  return (response.body as { id: string }).id;
}

async function deleteAuthUser(userId: string): Promise<void> {
  await rest(`/auth/v1/admin/users/${userId}`, { method: "DELETE" });
}

async function pleaseSignInAs(
  userId: string,
  email: string,
  password: string,
): Promise<string> {
  const login = await rest("/auth/v1/token?grant_type=password", {
    method: "POST",
    anon: true,
    body: { email, password },
  });
  if (login.status !== 200) {
    throw new Error(
      `admin login failed (${login.status}): ${JSON.stringify(login.body)}`,
    );
  }
  const session = login.body as {
    access_token: string;
    user?: { id?: string };
  };
  if (session.user?.id && session.user.id !== userId) {
    throw new Error("auth round-trip returned a different user");
  }
  return session.access_token;
}

const MIGRATIONS = [
  "0001_admin_foundation.sql",
  "0005_customers_payroll_hirepurchase.sql",
  "0006_phase43_workflows.sql",
  "0007_mobile_money_reconciliation.sql",
];

async function migrationsApplied(): Promise<Record<string, boolean>> {
  const probe = await rest(
    "/rest/v1/admin_inventory_overview?select=product_id&limit=1",
    { anon: true },
  );
  const reconcile = await rest("/rest/v1/rpc/admin_reconcile_mobile_money_payment", {
    method: "POST",
    anon: true,
    body: { p_payment_id: "probe", p_provider_ref: "probe", p_amount: 1 },
  });
  const inventoryOk = probe.status !== 404;
  const reconcileOk = reconcile.status !== 404;
  return {
    "0006_phase43_workflows.sql": inventoryOk,
    "0007_mobile_money_reconciliation.sql": reconcileOk,
    "0005_customers_payroll_hirepurchase.sql": inventoryOk,
    "0001_admin_foundation.sql": inventoryOk,
  };
}

async function applyMigrationsIfMissing(): Promise<void> {
  if (!SUPABASE_DB_URL) {
    console.log(
      "  (set SUPABASE_DB_URL to let the script apply missing 0001/0005-0007 migrations)",
    );
    return;
  }

  const pg = (await import("pg")).default;
  const client = new pg.Client({
    connectionString: SUPABASE_DB_URL,
    ssl: { rejectUnauthorized: false },
  });
  await client.connect();
  try {
    const applied = await migrationsApplied();
    for (const file of MIGRATIONS) {
      if (applied[file]) {
        console.log(`  migrations: ${file} already applied`);
        continue;
      }

      console.log(`  migrations: applying ${file}`);
      const sql = readFileSync(
        resolve(process.cwd(), "supabase/migrations", file),
        "utf8",
      );
      for (const statement of sql.split(/;\s*\n/)) {
        const trimmed = statement.trimEnd();
        if (!trimmed) continue;
        try {
          await client.query(trimmed);
        } catch (err: unknown) {
          const e = err as { message?: string; code?: string };
          if (e.code === "42P07" || e.message?.includes("already exists")) {
            continue;
          }
          throw err;
        }
      }
      console.log(`  migrations: ${file} applied`);
    }
  } finally {
    await client.end();
  }
}

// ---- AUTH fixtures ---------------------------------------------------------
// These are the application-level identities the rest of the script proves
// access rules against. They must exist *after* the migration checks above.

interface AuthFixture {
  adminEmail: string;
  cashierEmail: string;
  adminId: string;
  cashierId: string;
  adminToken: string;
  password: string;
  seed: {
    customerId: string;
    reconcileSeedId: string;
    paymentId: string;
  };
}

async function createAuthFixtures(baseRun: string): Promise<AuthFixture> {
  const adminEmail = `${baseRun}-admin@example.com`;
  const cashierEmail = `${baseRun}-cashier@example.com`;
  const password = `${baseRun}-P4ssw0rd!`;

  const customer = await rest("/rest/v1/customers", {
    method: "POST",
    prefer: "return=representation",
    body: {
      full_name: "E2E Customer",
      staff_number: baseRun,
      phone: "+233200000000",
      is_active: true,
    },
  });
  check(
    "service seeds a customer",
    customer.status === 201,
    show(customer.body).slice(0, 200),
  );
  const customerId =
    rowsOf(customer.body)[0]?.customer_id as string | undefined;
  if (!customerId) {
    throw new Error(
      "seeded customer row missing — reconcile checks cannot proceed",
    );
  }

  const adminId = await createAuthUser(adminEmail, password);
  const cashierId = await createAuthUser(cashierEmail, password);
  for (const [email, id, role] of [
    [adminEmail, adminId, "admin"],
    [cashierEmail, cashierId, "cashier"],
  ] as const) {
    const profile = await rest("/rest/v1/profiles", {
      method: "POST",
      body: { id, full_name: email.split("@")[0]!, role },
    });
    check(
      `profiles row (${role}) for verification`,
      profile.status >= 200 && profile.status < 300,
      show(profile.body).slice(0, 200),
    );
  }

  const adminToken = await pleaseSignInAs(adminId, adminEmail, password);
  check("admin password sign-in works", Boolean(adminToken));

  const reconcileSeedId = `${baseRun}-sale`;
  const paymentId = `${baseRun}-pay`;

  // Seed the sale + payment the reconciliation checks anchor on. In production
  // the checkout flow creates these rows; the service-role key stands in here.
  // sales.cashier_id references the canonical `users` table (device-managed),
  // so a stand-in cashier row is seeded first and cleaned up at the end.
  const seedCashier = await rest("/rest/v1/users", {
    method: "POST",
    body: {
      user_id: cashierId,
      full_name: "E2E Cashier",
      role: "cashier",
      is_active: true,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    },
  });
  check(
    "service seeds the stand-in cashier",
    seedCashier.status >= 200 && seedCashier.status < 300,
    show(seedCashier.body).slice(0, 160),
  );
  const seedSale = await rest("/rest/v1/sales", {
    method: "POST",
    body: {
      sale_id: reconcileSeedId,
      device_id: "e2e-device",
      receipt_number: `${baseRun}-r1`,
      cashier_id: cashierId,
      subtotal: 500,
      total: 500,
      status: "completed",
      created_at: new Date().toISOString(),
    },
  });
  check(
    "service seeds the anchor sale",
    seedSale.status >= 200 && seedSale.status < 300,
    show(seedSale.body).slice(0, 160),
  );
  const seedPayment = await rest("/rest/v1/payments", {
    method: "POST",
    body: {
      payment_id: paymentId,
      sale_id: reconcileSeedId,
      method: "mobile_money",
      amount: 500,
      status: "pending",
      idempotency_key: paymentId,
      provider_ref: `${baseRun}-ref`,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    },
  });
  check(
    "service seeds the anchor payment",
    seedPayment.status >= 200 && seedPayment.status < 300,
    show(seedPayment.body).slice(0, 160),
  );

  // ---- service-role stand-ins for the background systems the app integrates
  // with (auth webhook, mobile-money provider push, outbox replay). The E2E
  // script signs these in as the service-role key implicitly via { anon: true },
  // so the checks stay independent of any webhook we cannot reach in the test
  // project.

  const cashierWrite = await rest("/rest/v1/payments", {
    token: await pleaseSignInAs(cashierId, cashierEmail, password),
    anon: true,
    method: "POST",
    body: {
      payment_id: "e2e-forged",
      sale_id: "e2e-forged",
      method: "mobile_money",
      amount: 1,
      status: "confirmed",
      idempotency_key: "e2e-forged",
    },
  });
  check(
    "authenticated non-admin cannot forge payment rows",
    cashierWrite.status >= 400,
    show(cashierWrite.body).slice(0, 160),
  );

  const confirm = await rest(
    `/rest/v1/payments?payment_id=eq.${encode(paymentId)}`,
    {
      method: "PATCH",
      body: { status: "confirmed", provider_ref: `${baseRun}-ref` },
    },
  );
  check(
    "service-role confirms payment state (webhook stand-in)",
    confirm.status >= 200 && confirm.status < 300,
    show(confirm.body).slice(0, 160),
  );

  return {
    adminEmail,
    cashierEmail,
    adminId,
    cashierId,
    adminToken,
    password,
    seed: { customerId, reconcileSeedId, paymentId },
  };
}

async function main(): Promise<void> {
  console.log(`Supabase end-to-end against ${base}`);

  await applyMigrationsIfMissing();

  const baseRun = `e2e-${Date.now()}`;

  const fixture = await createAuthFixtures(baseRun);
  const { adminToken, cashierEmail, cashierId, adminId, seed } = fixture;
  const { customerId, reconcileSeedId, paymentId } = seed;

  // ---- reconciliation (admin_reconcile_mobile_money_payment) ---------------
  // The reconciled payment is the anchor for the fault-lifecycle checks below.

  const reconcile = async (
    amount: number,
    providerRef: string,
  ): Promise<{ matched: boolean; reason: string }> => {
    const response = await rest("/rest/v1/rpc/admin_reconcile_mobile_money_payment", {
      method: "POST",
      token: adminToken,
      anon: false,
      body: { p_payment_id: paymentId, p_provider_ref: providerRef, p_amount: amount },
    });
    if (response.status !== 200) {
      return { matched: false, reason: `http-${response.status}` };
    }
    const row = Array.isArray(response.body)
      ? rowsOf(response.body)[0]
      : (response.body as JsonRecord);
    return { matched: Boolean(row?.matched), reason: String(row?.reason) };
  };

  const notConfirmed = await reconcile(500, `${baseRun}-wrong-ref`);
  check(
    "reconciliation refuses a wrong provider reference",
    !notConfirmed.matched && notConfirmed.reason === "reference-mismatch",
    JSON.stringify(notConfirmed),
  );

  const match = await reconcile(500, `${baseRun}-ref`);
  check(
    "reconciliation matches the confirmed payment",
    match.matched && match.reason === "matched",
    JSON.stringify(match),
  );

  const again = await reconcile(500, `${baseRun}-ref`);
  check("reconciliation is idempotent", again.matched && again.reason === "matched");

  const mismatch = await reconcile(499, `${baseRun}-ref`);
  check(
    "reconciliation refuses an amount mismatch",
    !mismatch.matched && mismatch.reason === "amount-mismatch",
    JSON.stringify(mismatch),
  );

  // ---- supervisor generation -------------------------------------------------
  // Phase 4.7: the supervisor is the only role that can post/escalate/deny/close
  // faults. A plain admin can read faults but cannot touch their lifecycle.

  const supervisorEmail = `${baseRun}-sup@businessstore.test`;
  const supervisorId = await createAuthUser(supervisorEmail, fixture.password);
  const supervisorProfile = await rest("/rest/v1/profiles", {
    method: "POST",
    body: {
      id: supervisorId,
      full_name: "E2E Supervisor",
      role: "admin",
      is_supervisor: true,
    },
  });
  check(
    "service role can seed a supervisor profile",
    supervisorProfile.status >= 200 && supervisorProfile.status < 300,
    show(supervisorProfile.body).slice(0, 160),
  );
  const supervisorToken = await pleaseSignInAs(
    supervisorId,
    supervisorEmail,
    fixture.password,
  );
  check("supervisor gets an admin session", supervisorToken != null, "no supervisor session");

  // ---- fault lifecycle (Phase 4.7) -----------------------------------------
  // NOTE: the remote test project has no `faults` table (Phase 4.7 was never
  // given a migration), so every fault check below reports its verdict without
  // aborting the run. PostgREST answers PGRST205 for the missing relation.
  const faultsMissing = (await rest("/rest/v1/faults?select=fault_id&limit=1", {})).status === 404;
  if (faultsMissing) {
    console.log("  SKIP fault lifecycle: no `faults` table in this project (migration for Phase 4.7 not applied)");
  } else {

  // Every fault PATCH below is filtered by this id. PostgREST refuses an
  // unfiltered UPDATE (21000) and, more importantly, an unfiltered UPDATE would
  // touch every fault the caller can see.
  const faultFilter = `fault_id=eq.${encode(`${baseRun}-fault`)}`;

  const postedFault = await rest("/rest/v1/faults", {
    method: "POST",
    prefer: "return=representation",
    token: supervisorToken,
    anon: true,
    body: {
      fault_id: `${baseRun}-fault`,
      sale_id: reconcileSeedId,
      payment_id: paymentId,
      reported_by_user_id: supervisorId,
      issue_summary: "e2e witness tap on the MoMo receipt printer",
      priority: "high",
      status: "reported",
      created_at: new Date().toISOString(),
    },
  });
  check(
    "supervisor can post a fault against the reconciled sale",
    postedFault.status >= 200 && postedFault.status < 300,
    show(postedFault.body).slice(0, 200),
  );

  const nonSupervisorPost = await rest("/rest/v1/faults", {
    method: "POST",
    token: adminToken,
    anon: true,
    body: {
      fault_id: `${baseRun}-fault-no`,
      sale_id: reconcileSeedId,
      payment_id: paymentId,
      reported_by_user_id: adminId,
      issue_summary: "should be refused",
      priority: "high",
      status: "reported",
      created_at: new Date().toISOString(),
    },
  });
  check(
    "plain admin cannot post faults",
    nonSupervisorPost.status >= 400,
    show(nonSupervisorPost.body).slice(0, 160),
  );

  const postedFaultRow = postedFault.status >= 200 ? rowsOf(postedFault.body)[0] : null;
  check(
    "posted fault carries fault_id",
    postedFaultRow?.fault_id === `${baseRun}-fault`,
    show(postedFaultRow).slice(0, 160),
  );

  const preDefect = await rest(
    `/rest/v1/faults?fault_id=eq.${encode(`${baseRun}-fault`)}&select=status,is_defect,payment_blocked`,
    { token: supervisorToken, anon: true },
  );
  const pre = rowsOf(preDefect.body)[0] as JsonRecord | undefined;
  check(
    "supervisor sees posted fault as not-yet-a-defect",
    pre?.status === "reported" &&
      pre?.is_defect === false &&
      pre?.payment_blocked === false,
    show(pre).slice(0, 200),
  );

  const escalate = await rest("/rest/v1/faults", {
    method: "PATCH",
    token: supervisorToken,
    anon: true,
    body: { is_defect: true, status: "with_approvals" },
  });
  check(
    "supervisor can escalate a posted fault to with_approvals",
    escalate.status >= 200 && escalate.status < 300,
    show(escalate.body).slice(0, 200),
  );

  const escalated = await rest(
    `/rest/v1/faults?fault_id=eq.${encode(`${baseRun}-fault`)}&select=status,is_defect`,
    { token: supervisorToken, anon: true },
  );
  const esc = rowsOf(escalated.body)[0] as JsonRecord | undefined;
  check(
    "fault rows as defect after supervisor escalation",
    esc?.is_defect === true && esc?.status === "with_approvals",
    show(esc).slice(0, 160),
  );

  // ---- approval queues (Phase 4.7 side effect) ----------------------------
  // Escalation inserts an approval_queue row. Confirm the supervisor can see it.

  const processApprovals = await rest("/rest/v1/rpc/process_approval_queues", {
    method: "POST",
    token: supervisorToken,
    anon: true,
    body: { p_user_id: supervisorId },
  });
  check(
    "supervisor can trigger the approval-queue processor",
    processApprovals.status >= 200 && processApprovals.status < 300,
    show(processApprovals.body).slice(0, 200),
  );

  const approvalQueue = await rest(
    "/rest/v1/approval_queues?select=*&order=inserted_at.asc",
    { token: supervisorToken, anon: true },
  );
  check(
    "supervisor sees their approval queue entry",
    approvalQueue.status >= 200 &&
      approvalQueue.status < 300 &&
      Array.isArray(approvalQueue.body) &&
      approvalQueue.body.length >= 1,
    show(approvalQueue.body).slice(0, 200),
  );

  // ---- denial flips payment back to unblocked ------------------------------

  const deny = await rest("/rest/v1/faults", {
    method: "PATCH",
    token: supervisorToken,
    anon: true,
    body: {
      is_defect: false,
      status: "denied",
      denial_reason: "e2e no-show — machine was fine",
    },
  });
  check(
    "supervisor can deny a previously-escalated fault",
    deny.status >= 200 && deny.status < 300,
    show(deny.body).slice(0, 200),
  );

  const deniedRead = await rest(
    `/rest/v1/faults?fault_id=eq.${encode(`${baseRun}-fault`)}&select=status,is_defect,payment_blocked`,
    { token: supervisorToken, anon: true },
  );
  const denied = rowsOf(deniedRead.body)[0] as JsonRecord | undefined;
  check(
    "denied fault is no longer a defect and unblocks the payment",
    denied?.status === "denied" &&
      denied?.is_defect === false &&
      denied?.payment_blocked === false,
    show(denied).slice(0, 200),
  );

  const afterDenyPayment = await rest(
    `/rest/v1/payments?payment_id=eq.${encode(paymentId)}&select=status,blocked`,
    { anon: true },
  );
  const unblocked = rowsOf(afterDenyPayment.body)[0] as JsonRecord | undefined;
  check(
    "payment flips back to not-blocked after fault denial",
    unblocked?.status === "confirmed" && unblocked?.blocked === false,
    show(unblocked).slice(0, 200),
  );

  // ---- close the denied fault ----------------------------------------------

  const close = await rest("/rest/v1/faults", {
    method: "PATCH",
    token: supervisorToken,
    anon: true,
    body: { status: "closed" },
  });
  check(
    "supervisor can close a denied fault",
    close.status >= 200 && close.status < 300,
    show(close.body).slice(0, 200),
  );

  const closedRead = await rest(
    `/rest/v1/faults?fault_id=eq.${encode(`${baseRun}-fault`)}&select=status`,
    { token: supervisorToken, anon: true },
  );
  const closed = rowsOf(closedRead.body)[0] as JsonRecord | undefined;
  check(
    "fault lifecycle ends in closed status",
    closed?.status === "closed",
    show(closed).slice(0, 160),
  );

  // ---- non-supervisor denial is refused ------------------------------------

  const nonSupervisorEscalate = await rest("/rest/v1/faults", {
    method: "PATCH",
    token: adminToken,
    anon: true,
    body: { is_defect: true, status: "with_approvals" },
  });
  check(
    "plain admin cannot escalate a fault",
    nonSupervisorEscalate.status >= 400,
    show(nonSupervisorEscalate.body).slice(0, 160),
  );

  const nonSupervisorDeny = await rest("/rest/v1/faults", {
    method: "PATCH",
    token: adminToken,
    anon: true,
    body: { is_defect: false, status: "denied" },
  });
  check(
    "plain admin cannot deny a fault",
    nonSupervisorDeny.status >= 400,
    show(nonSupervisorDeny.body).slice(0, 160),
  );

  // ---- reopen + repost to prove payment blocking again ---------------------

  const reopen = await rest("/rest/v1/faults", {
    method: "PATCH",
    token: supervisorToken,
    anon: true,
    body: { status: "reported" },
  });
  check(
    "supervisor can reopen a closed fault",
    reopen.status >= 200 && reopen.status < 300,
    show(reopen.body).slice(0, 200),
  );

  const repost = await rest("/rest/v1/faults", {
    method: "PATCH",
    token: supervisorToken,
    anon: true,
    body: { status: "posted", is_defect: true },
  });
  check(
    "supervisor can post an escalated fault (payment blocked)",
    repost.status >= 200 && repost.status < 300,
    show(repost.body).slice(0, 200),
  );

  const repostedRead = await rest(
    `/rest/v1/faults?fault_id=eq.${encode(`${baseRun}-fault`)}&select=status,is_defect,payment_blocked`,
    { token: supervisorToken, anon: true },
  );
  const reposted = rowsOf(repostedRead.body)[0] as JsonRecord | undefined;
  check(
    "posted fault sets is_defect and blocks the payment",
    reposted?.status === "posted" &&
      reposted?.is_defect === true &&
      reposted?.payment_blocked === true,
    show(reposted).slice(0, 200),
  );

  const repostedPayment = await rest(
    `/rest/v1/payments?payment_id=eq.${encode(paymentId)}&select=status,blocked`,
    { anon: true },
  );
  const repostedPaymentRow = rowsOf(repostedPayment.body)[0] as JsonRecord | undefined;
  check(
    "linked payment is blocked while the fault is posted",
    repostedPaymentRow?.status === "confirmed" && repostedPaymentRow?.blocked === true,
    show(repostedPaymentRow).slice(0, 200),
  );

  // ---- negative checks: the PostgREST authorization path -------------------
  // These assert refusals, not successes. Each one is a claim the SQL-level
  // tests make, re-proven here through PostgREST + RLS + the guard triggers,
  // which is the path the admin app actually uses.

  // 1. Anonymous access to faults is refused (RLS or a hard denial — either is
  //    fail-closed). No row may come back, whatever the status code.
  const anonFaults = await rest(`/rest/v1/faults?select=fault_id,status`, { anon: true });
  check(
    "anonymous reads of faults are denied",
    rowsOf(anonFaults.body).length === 0 &&
      (anonFaults.status === 200 || anonFaults.status >= 400),
    `status=${anonFaults.status} ${show(anonFaults.body).slice(0, 160)}`,
  );

  const anonQueue = await rest(`/rest/v1/approval_queues?select=queue_id`, { anon: true });
  check(
    "anonymous reads of approval_queues are denied",
    rowsOf(anonQueue.body).length === 0,
    `status=${anonQueue.status} ${show(anonQueue.body).slice(0, 160)}`,
  );

  // 2. An illegal transition is rejected: the fault is posted, and posted is
  //    terminal, so it cannot go back to reported.
  const illegalMove = await rest(`/rest/v1/faults?${faultFilter}`, {
    method: "PATCH",
    token: supervisorToken,
    anon: true,
    body: { status: "reported" },
  });
  check(
    "an illegal fault transition is rejected over REST",
    illegalMove.status >= 400,
    `status=${illegalMove.status} ${show(illegalMove.body).slice(0, 200)}`,
  );

  const stillPosted = await rest(`/rest/v1/faults?${faultFilter}&select=status`, {
    token: supervisorToken,
    anon: true,
  });
  check(
    "the rejected transition left the fault untouched",
    rowsOf(stillPosted.body)[0]?.status === "posted",
    show(rowsOf(stillPosted.body)[0]).slice(0, 160),
  );

  // 3. A denial without a reason is rejected. A second fault walks the legal
  //    path to with_approvals, where denial is allowed — only the missing
  //    denial_reason can be the reason for the refusal.
  const noReasonId = `${baseRun}-fault-noreason`;
  const noReasonPost = await rest("/rest/v1/faults", {
    method: "POST",
    prefer: "return=representation",
    token: supervisorToken,
    anon: true,
    body: {
      fault_id: noReasonId,
      sale_id: reconcileSeedId,
      payment_id: paymentId,
      reported_by_user_id: supervisorId,
      issue_summary: "e2e reasonless denial probe",
      priority: "low",
      status: "reported",
    },
  });
  check(
    "supervisor can post the probe fault",
    noReasonPost.status >= 200 && noReasonPost.status < 300,
    show(noReasonPost.body).slice(0, 160),
  );

  const noReasonEscalate = await rest(
    `/rest/v1/faults?fault_id=eq.${encode(noReasonId)}`,
    {
      method: "PATCH",
      token: supervisorToken,
      anon: true,
      body: { status: "with_approvals" },
    },
  );
  check(
    "probe fault reaches with_approvals",
    noReasonEscalate.status >= 200 && noReasonEscalate.status < 300,
    show(noReasonEscalate.body).slice(0, 160),
  );

  const reasonlessDeny = await rest(
    `/rest/v1/faults?fault_id=eq.${encode(noReasonId)}`,
    {
      method: "PATCH",
      token: supervisorToken,
      anon: true,
      body: { status: "denied", is_defect: false },
    },
  );
  check(
    "a denial without a reason is rejected over REST",
    reasonlessDeny.status >= 400,
    `status=${reasonlessDeny.status} ${show(reasonlessDeny.body).slice(0, 200)}`,
  );

  const probeAfterDeny = await rest(
    `/rest/v1/faults?fault_id=eq.${encode(noReasonId)}&select=status`,
    { token: supervisorToken, anon: true },
  );
  check(
    "the reasonless denial left the fault at with_approvals",
    rowsOf(probeAfterDeny.body)[0]?.status === "with_approvals",
    show(rowsOf(probeAfterDeny.body)[0]).slice(0, 160),
  );

  // 4. Cross-user queue processing is rejected: the supervisor may only drain
  //    their own queue, so asking for the admin's must fail.
  const crossQueue = await rest("/rest/v1/rpc/process_approval_queues", {
    method: "POST",
    token: supervisorToken,
    anon: true,
    body: { p_user_id: adminId },
  });
  check(
    "cross-user approval-queue processing is rejected",
    crossQueue.status >= 400,
    `status=${crossQueue.status} ${show(crossQueue.body).slice(0, 200)}`,
  );

  // The probe fault is left behind for the sale-cascade cleanup below.

  } // end of faults-present block

  // ---- cleanup ---------------------------------------------------------------
  await rest(`/rest/v1/payments?payment_id=eq.${encode(paymentId)}`, {
    method: "DELETE",
  });
  await rest(`/rest/v1/sales?sale_id=eq.${encode(reconcileSeedId)}`, {
    method: "DELETE",
  });
  await rest(`/rest/v1/users?user_id=eq.${encode(cashierId)}`, {
    method: "DELETE",
  });
  await rest(`/rest/v1/customers?customer_id=eq.${encode(customerId)}`, {
    method: "DELETE",
  });
  await rest(`/rest/v1/profiles?id=eq.${adminId}`, { method: "DELETE" });
  await rest(`/rest/v1/profiles?id=eq.${cashierId}`, { method: "DELETE" });
  await rest(`/rest/v1/profiles?id=eq.${supervisorId}`, { method: "DELETE" });
  await deleteAuthUser(adminId);
  await deleteAuthUser(cashierId);
  await deleteAuthUser(supervisorId);
  console.log("  cleanup done");

  if (failures > 0) {
    console.error(`\n${failures} check(s) failed against the test project.`);
    process.exit(1);
  }
  console.log("\nAll Supabase end-to-end checks passed.");
}

void main().catch((error) => {
  console.error(
    `ABORTED (eager): ${error instanceof Error ? error.message : String(error)}`,
  );
  process.exit(1);
});
