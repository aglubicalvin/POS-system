package com.businessstore.pos

import com.businessstore.pos.db.Database
import com.businessstore.pos.db.Schema
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves the committed migration set replays on a **clean** database.
 *
 * Every test here creates its own throwaway database inside the embedded
 * PostgreSQL cluster, bootstraps only the canonical POS schema plus the Supabase
 * shim (roles, `auth.users`, `auth.uid()`), and then applies
 * `supabase/migrations/0001..0009` in filename order — one `execute` per file,
 * no manual steps, no repair statements. The development project is never
 * touched: this cluster is a disposable local instance.
 *
 * It also exercises the rollbacks on the same throwaway database, because a
 * rollback that has never been run is a guess.
 */
class CleanReplayMigrationTest {

    // ------------------------------------------------------------------
    // Disposable database
    // ------------------------------------------------------------------

    private class Replay(val db: Database, val name: String)

    private fun replay(): Replay {
        val name = "clean_replay_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val url = TestDb.dataSource.connection.use { conn ->
            val meta = conn.metaData
            val base = meta.url
            val user = meta.userName
            conn.createStatement().use { it.execute("CREATE DATABASE $name") }
            base to user
        }
        val withoutQuery = url.first.substringBefore('?')
        val trim = withoutQuery.substringBeforeLast('/')
        val tail = if (url.first.contains('?')) "?" + url.first.substringAfter('?') else ""
        val config = HikariConfig().apply {
            jdbcUrl = "$trim/$name$tail"
            username = url.second
            password = ""
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 2
            poolName = "clean-replay-$name"
        }
        val db = Database(HikariDataSource(config))
        return Replay(db, name)
    }

    private fun discard(replay: Replay) {
        replay.db.close()
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS ${replay.name}") }
        }
    }

    /** Canonical schema, then the shim, then 0001-0009 in order. No intervention. */
    private fun bootstrap(replay: Replay) {
        replay.db.read { conn -> SupabaseMigrations.installSupabaseShim(conn) }
        Schema.apply(replay.db)
        grantClientAccess(replay.db)
        for (file in MIGRATIONS) apply(replay.db, file)
        // Supabase's default privileges cover tables created by later migrations.
        grantClientAccess(replay.db)
    }

    private fun apply(db: Database, file: String) {
        val sql = Files.readString(SupabaseMigrations.migrationsDir().resolve(file))
        db.read { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    private fun grantClientAccess(db: Database) {
        db.read { conn ->
            conn.createStatement().use {
                it.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO authenticated, anon")
                it.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO authenticated, anon")
            }
        }
    }

    // ------------------------------------------------------------------
    // Introspection helpers
    // ------------------------------------------------------------------

    private fun <T> query(db: Database, sql: String, block: (ResultSet) -> T): T =
        db.read { conn -> conn.createStatement().use { st -> st.executeQuery(sql).use(block) } }

    private fun scalarString(db: Database, sql: String): String? =
        query(db, sql) { rs -> if (rs.next()) rs.getString(1) else null }

    private fun tableExists(db: Database, name: String): Boolean =
        scalarString(db, "SELECT to_regclass('public.$name')::text") != null

    private fun functionExists(db: Database, signature: String): Boolean =
        scalarString(db, "SELECT to_regprocedure('$signature')::text") != null

    private fun columns(db: Database, table: String): Set<String> = query(
        db,
        """
        SELECT column_name FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = '$table'
        """.trimIndent(),
    ) { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }

    /** `roles::text` is Postgres' array literal, e.g. `{authenticated}` or `{public}`. */
    private fun policyRoles(db: Database, policy: String): String? = scalarString(
        db,
        "SELECT roles::text FROM pg_policies WHERE schemaname = 'public' AND policyname = '$policy'",
    )

    private fun <T> asRole(db: Database, role: String, userId: UUID?, block: (Connection) -> T): T =
        db.read { conn ->
            conn.createStatement().use { it.execute("SET ROLE $role") }
            try {
                conn.prepareStatement("SELECT set_config('request.jwt.claim.sub', ?, false)").use { st ->
                    st.setString(1, userId?.toString() ?: "")
                    st.execute()
                }
                block(conn)
            } finally {
                conn.createStatement().use { it.execute("RESET ROLE") }
            }
        }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun seedIdentity(db: Database, role: String, supervisor: Boolean): UUID {
        val id = UUID.randomUUID()
        db.transaction { conn ->
            conn.prepareStatement("INSERT INTO auth.users (id) VALUES (?)").use { st ->
                st.setObject(1, id)
                st.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO profiles (id, full_name, role, is_active, is_supervisor) VALUES (?, ?, ?, TRUE, ?)",
            ).use { st ->
                st.setObject(1, id)
                st.setString(2, "Replay $role")
                st.setString(3, role)
                st.setBoolean(4, supervisor)
                st.executeUpdate()
            }
        }
        return id
    }

    private fun seedSaleAndPayment(db: Database): Pair<String, String> {
        val saleId = "replay-sale"
        val paymentId = "replay-payment"
        val cashierId = "replay-cashier"
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (user_id, device_id, full_name, role, mfa_enabled, failed_attempts,
                                   is_active, created_at, updated_at)
                VALUES (?, NULL, 'Replay Cashier', 'cashier', FALSE, 0, TRUE, now(), now())
                """.trimIndent(),
            ).use { st ->
                st.setString(1, cashierId)
                st.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO sales (sale_id, device_id, receipt_number, cashier_id, currency, subtotal,
                                   discount_total, tax_total, total, kind, status, sync_state, created_at)
                VALUES (?, 'replay-device', 'REPLAY-0001', ?, 'GHS', 500, 0, 0, 500, 'sale', 'completed',
                        'synced', now())
                """.trimIndent(),
            ).use { st ->
                st.setString(1, saleId)
                st.setString(2, cashierId)
                st.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO payments (payment_id, sale_id, method, amount, status, idempotency_key,
                                      provider, provider_ref, created_at, updated_at)
                VALUES (?, ?, 'mobile_money', 500, 'confirmed', 'replay-key', 'mtn', 'MM-REPLAY', now(), now())
                """.trimIndent(),
            ).use { st ->
                st.setString(1, paymentId)
                st.setString(2, saleId)
                st.executeUpdate()
            }
        }
        return saleId to paymentId
    }

    private fun postFault(
        db: Database,
        supervisorId: UUID,
        saleId: String,
        paymentId: String,
        faultId: String = "replay-fault",
    ): String {
        asRole(db, "authenticated", supervisorId) { conn ->
            conn.prepareStatement(
                """
                INSERT INTO public.faults (fault_id, sale_id, payment_id, reported_by_user_id,
                                           issue_summary, priority, status)
                VALUES (?, ?, ?, ?, 'replay witness tap', 'high', 'reported')
                """.trimIndent(),
            ).use { st ->
                st.setString(1, faultId)
                st.setString(2, saleId)
                st.setString(3, paymentId)
                st.setObject(4, supervisorId)
                st.executeUpdate()
            }
        }
        return faultId
    }

    private fun faultStatus(db: Database, faultId: String): String? =
        scalarString(db, "SELECT status FROM public.faults WHERE fault_id = '$faultId'")

    private fun paymentBlocked(db: Database, paymentId: String): Boolean? =
        scalarString(db, "SELECT blocked::text FROM public.payments WHERE payment_id = '$paymentId'")?.toBoolean()

    private fun moveFault(db: Database, userId: UUID, faultId: String, setClause: String): Int =
        asRole(db, "authenticated", userId) { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("UPDATE public.faults SET $setClause WHERE fault_id = '$faultId'")
            }
        }

    // ------------------------------------------------------------------
    // 1. Clean replay
    // ------------------------------------------------------------------

    @Test
    fun `0001 to 0009 apply in order to a clean database`() {
        val replay = replay()
        try {
            bootstrap(replay)
            val db = replay.db

            // Canonical objects the migrations build on are still there.
            for (table in listOf("sales", "sale_items", "payments", "products", "stock_on_hand", "audit_log")) {
                assertTrue(tableExists(db, table), "canonical table $table missing")
            }

            // Every object 0001-0009 advertises.
            for (table in listOf(
                "profiles", "product_categories", "product_price_audit", "customers",
                "payroll_deductions", "hire_purchase_contracts", "hire_purchase_payments",
                "approval_queues", "faults",
            )) {
                assertTrue(tableExists(db, table), "migration table $table missing")
            }
            for (fn in listOf(
                "public.is_admin()", "public.is_supervisor()", "public.process_approval_queues(uuid)",
                "public.admin_reconcile_mobile_money_payment(text,text,bigint)",
                "public.faults_guard_transition()", "public.faults_sync_payment_block()",
                "public.faults_escalation_queue()",
            )) {
                assertTrue(functionExists(db, fn), "function $fn missing")
            }
            assertTrue("blocked" in columns(db, "payments"), "payments.blocked missing")
            assertTrue("is_supervisor" in columns(db, "profiles"), "profiles.is_supervisor missing")

            // 0008: all 15 is_admin() policies are scoped to authenticated.
            val wrong = ADMIN_POLICIES.filter { policyRoles(db, it) != "{authenticated}" }
            assertTrue(wrong.isEmpty(), "policies not retargeted to authenticated: $wrong")
            assertEquals(15, ADMIN_POLICIES.size)

            // profiles_self_read is deliberately left on public.
            assertEquals("{public}", policyRoles(db, "profiles_self_read"))
        } finally {
            discard(replay)
        }
    }

    @Test
    fun `profiles_self_read still returns only the caller's own row`() {
        val replay = replay()
        try {
            bootstrap(replay)
            val db = replay.db
            val me = seedIdentity(db, "cashier", supervisor = false)
            val other = seedIdentity(db, "cashier", supervisor = false)

            val visible = asRole(db, "authenticated", me) { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT id::text FROM public.profiles").use { rs ->
                        buildSet { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
            assertEquals(setOf(me.toString()), visible, "self-read leaked another profile")
            assertFalse(visible.contains(other.toString()))
        } finally {
            discard(replay)
        }
    }

    // ------------------------------------------------------------------
    // 2. Supervisor authorization
    // ------------------------------------------------------------------

    @Test
    fun `supervisor authorization distinguishes the flag from the admin role`() {
        val replay = replay()
        try {
            bootstrap(replay)
            val db = replay.db
            val supervisor = seedIdentity(db, "admin", supervisor = true)
            val plainAdmin = seedIdentity(db, "admin", supervisor = false)
            val flaggedCashier = seedIdentity(db, "cashier", supervisor = true)

            fun isSupervisor(id: UUID) = asRole(db, "authenticated", id) { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT public.is_supervisor()").use { rs -> rs.next(); rs.getBoolean(1) }
                }
            }

            assertTrue(isSupervisor(supervisor), "flagged admin must be a supervisor")
            assertFalse(isSupervisor(plainAdmin), "plain admin must not be a supervisor")
            assertFalse(isSupervisor(flaggedCashier), "the flag alone must not grant supervision")
        } finally {
            discard(replay)
        }
    }

    // ------------------------------------------------------------------
    // 3. Fault lifecycle, approval queue, payment blocking
    // ------------------------------------------------------------------

    @Test
    fun `fault lifecycle drives the approval queue and the payment block`() {
        val replay = replay()
        try {
            bootstrap(replay)
            val db = replay.db
            val supervisor = seedIdentity(db, "admin", supervisor = true)
            val plainAdmin = seedIdentity(db, "admin", supervisor = false)
            val (saleId, paymentId) = seedSaleAndPayment(db)
            val faultId = postFault(db, supervisor, saleId, paymentId)

            assertEquals("reported", faultStatus(db, faultId))
            assertEquals(false, paymentBlocked(db, paymentId))

            // Escalation feeds the supervisor's approval queue.
            assertEquals(1, moveFault(db, supervisor, faultId, "status = 'with_approvals'"))
            val queued = scalarString(
                db,
                "SELECT count(*)::text FROM public.approval_queues WHERE entity_id = '$faultId' AND processed_at IS NULL",
            )
            assertEquals("1", queued, "escalation must insert one approval-queue row")

            // Posting the defect blocks the linked payment.
            assertEquals(1, moveFault(db, supervisor, faultId, "status = 'posted', is_defect = TRUE"))
            assertEquals("posted", faultStatus(db, faultId))
            assertEquals(true, paymentBlocked(db, paymentId), "posted defect must block the payment")

            // Withdrawing the defect while still posted unblocks the payment again.
            assertEquals(1, moveFault(db, supervisor, faultId, "is_defect = FALSE"))
            assertEquals(false, paymentBlocked(db, paymentId), "clearing is_defect must unblock the payment")
            assertEquals(1, moveFault(db, supervisor, faultId, "is_defect = TRUE"))
            assertEquals(true, paymentBlocked(db, paymentId))

            // A plain admin can read the fault but cannot move it.
            val adminUpdates = moveFault(db, plainAdmin, faultId, "status = 'closed'")
            assertEquals(0, adminUpdates, "RLS must hide the row from a plain admin's update")
            assertEquals("posted", faultStatus(db, faultId))

            // `posted` is terminal: a posted fault cannot jump back to reported,
            // and denial is only reachable from `with_approvals`.
            assertFailsWith<SQLException> { moveFault(db, supervisor, faultId, "status = 'reported'") }
            assertFailsWith<SQLException> {
                moveFault(db, supervisor, faultId, "status = 'denied', denial_reason = 'too late'")
            }

            // A second fault proves the reasonless-denial rule and denial unblocking.
            val secondFault = postFault(db, supervisor, saleId, paymentId, faultId = "replay-fault-2")
            assertEquals(1, moveFault(db, supervisor, secondFault, "status = 'with_approvals'"))
            assertFailsWith<SQLException> {
                moveFault(db, supervisor, secondFault, "status = 'denied', is_defect = FALSE")
            }
            assertEquals("with_approvals", faultStatus(db, secondFault))
            assertEquals(
                1,
                moveFault(
                    db,
                    supervisor,
                    secondFault,
                    "status = 'denied', is_defect = FALSE, denial_reason = 'no-show'",
                ),
            )
            assertEquals("denied", faultStatus(db, secondFault))
            assertFalse(
                scalarString(db, "SELECT payment_blocked::text FROM public.faults WHERE fault_id = '$secondFault'")!!.toBoolean(),
            )
            assertEquals(false, paymentBlocked(db, paymentId))
            assertEquals(1, moveFault(db, supervisor, secondFault, "status = 'closed'"))

            // The queue processor is limited to the caller's own queue.
            val processed = asRole(db, "authenticated", supervisor) { conn ->
                conn.prepareStatement("SELECT public.process_approval_queues(?)").use { st ->
                    st.setObject(1, supervisor)
                    st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }
            assertEquals(2, processed, "both escalations must be in the supervisor's queue")
            val remaining = scalarString(
                db,
                "SELECT count(*)::text FROM public.approval_queues WHERE processed_at IS NULL",
            )
            assertEquals("0", remaining)
        } finally {
            discard(replay)
        }
    }

    // ------------------------------------------------------------------
    // 4. Anonymous access
    // ------------------------------------------------------------------

    @Test
    fun `anonymous requests reach no protected rows and no privileged function`() {
        val replay = replay()
        try {
            bootstrap(replay)
            val db = replay.db
            val supervisor = seedIdentity(db, "admin", supervisor = true)
            val (saleId, paymentId) = seedSaleAndPayment(db)
            postFault(db, supervisor, saleId, paymentId)

            for (table in listOf("payments", "customers", "faults", "approval_queues", "profiles", "sales")) {
                val rows = asRole(db, "anon", null) { conn ->
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM public.$table").use { rs -> rs.next(); rs.getLong(1) }
                    }
                }
                assertEquals(0L, rows, "anon must not see $table")
            }

            // EXECUTE is revoked from anon on both 0009 helpers.
            assertFailsWith<SQLException> {
                asRole(db, "anon", null) { conn ->
                    conn.createStatement().use { it.execute("SELECT public.is_supervisor()") }
                }
            }
            assertFailsWith<SQLException> {
                asRole(db, "anon", null) { conn ->
                    conn.createStatement().use { it.execute("SELECT public.process_approval_queues('$supervisor')") }
                }
            }
        } finally {
            discard(replay)
        }
    }

    // ------------------------------------------------------------------
    // 5. Rollbacks, then reapply
    // ------------------------------------------------------------------

    @Test
    fun `0009 then 0008 roll back cleanly and reapply`() {
        val replay = replay()
        try {
            bootstrap(replay)
            val db = replay.db

            // ---- 0009 rollback: fault infrastructure is gone ----
            apply(db, "0009_phase47_faults.rollback.sql")
            for (table in listOf("faults", "approval_queues")) {
                assertFalse(tableExists(db, table), "$table survived the 0009 rollback")
            }
            for (fn in listOf(
                "public.is_supervisor()", "public.process_approval_queues(uuid)",
                "public.faults_guard_transition()", "public.faults_sync_payment_block()",
                "public.faults_escalation_queue()",
            )) {
                assertFalse(functionExists(db, fn), "$fn survived the 0009 rollback")
            }
            assertFalse("blocked" in columns(db, "payments"), "payments.blocked survived the 0009 rollback")
            assertFalse("is_supervisor" in columns(db, "profiles"), "profiles.is_supervisor survived the 0009 rollback")
            val leftoverTriggers = scalarString(
                db,
                """
                SELECT coalesce(string_agg(tgname, ','), '') FROM pg_trigger
                 WHERE NOT tgisinternal AND tgname = ANY (ARRAY[
                   'faults_transition_guard', 'faults_payment_block_sync', 'faults_escalation_queue_sync'])
                """.trimIndent(),
            )
            assertEquals("", leftoverTriggers, "fault triggers survived the 0009 rollback")

            // ---- 0008 rollback: the 15 policies are back on public ----
            apply(db, "0008_authorize_admin_policies.rollback.sql")
            val notPublic = ADMIN_POLICIES.filter { policyRoles(db, it) != "{public}" }
            assertTrue(notPublic.isEmpty(), "policies not restored to public: $notPublic")

            // ---- reapply both, in order, and prove the lifecycle again ----
            apply(db, "0008_authorize_admin_policies.sql")
            apply(db, "0009_phase47_faults.sql")
            // Supabase's default privileges cover the recreated tables too.
            grantClientAccess(db)
            val retargeted = ADMIN_POLICIES.filter { policyRoles(db, it) == "{authenticated}" }
            assertEquals(15, retargeted.size, "reapplied 0008 must retarget all 15 policies")

            val supervisor = seedIdentity(db, "admin", supervisor = true)
            val (saleId, paymentId) = seedSaleAndPayment(db)
            val faultId = postFault(db, supervisor, saleId, paymentId)
            assertEquals(1, moveFault(db, supervisor, faultId, "status = 'posted', is_defect = TRUE"))
            assertEquals(true, paymentBlocked(db, paymentId))
        } finally {
            discard(replay)
        }
    }

    private companion object {
        /** The committed migration files, applied in filename order. */
        val MIGRATIONS = listOf(
            "0001_admin_foundation.sql",
            "0002_product_management.sql",
            "0003_dashboard_aggregation_and_authz.sql",
            "0004_rpc_search_path_hardening.sql",
            "0005_customers_payroll_hirepurchase.sql",
            "0006_phase43_workflows.sql",
            "0007_mobile_money_reconciliation.sql",
            "0008_authorize_admin_policies.sql",
            "0009_phase47_faults.sql",
        )

        /** The 15 policies migration 0008 retargets. */
        val ADMIN_POLICIES = listOf(
            "profiles_admin_read", "sales_admin_read", "sale_items_read", "payments_read",
            "products_read", "stock_on_hand_admin_read", "approvals_read", "audit_admin_read",
            "categories_read_all", "categories_admin_all", "price_audit_admin_read",
            "customers_admin_all", "payroll_deductions_admin_all", "hp_contracts_admin_all",
            "hp_payments_admin_all",
        )
    }
}
