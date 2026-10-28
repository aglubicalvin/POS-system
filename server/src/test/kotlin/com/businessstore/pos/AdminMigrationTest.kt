package com.businessstore.pos

import com.businessstore.pos.db.Database
import com.businessstore.pos.db.Schema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavior of the admin-app layer added by the Supabase migrations, run against a
 * real PostgreSQL: the dashboard roll-up numbers and the is_admin() gate/grants.
 *
 * The embedded cluster has no Supabase-specific `auth` schema, so this test shims
 * the two objects the migrations need — auth.users and auth.uid() — reading the
 * JWT subject from the same `request.jwt.claim.sub` setting Supabase uses.
 */
class AdminMigrationTest {

    private val db: Database get() = Database.from(TestDb.dataSource)

    @BeforeEach
    fun reset() {
        Schema.apply(db)
        Migrations.ensure(db)
        db.transaction { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE profiles, auth.users, sale_items, sales, products, users CASCADE")
            }
        }
    }

    // ------------------------------------------------------------------
    // Migration 0003: dashboard roll-up must not fan out per line item
    // ------------------------------------------------------------------
    @Test
    fun `dashboard counts each ticket once regardless of line count`() {
        val milo = insertProduct("MILO-400", "Milo Refill 400g", unitPrice = 4500, costPrice = 3000)
        val water = insertProduct("WATER-500", "Sachet Water 500ml", unitPrice = 150, costPrice = 100)
        val cashier = insertUser("Ama", "cashier")

        // Day 1, ticket A: two lines (2 x Milo with a discount + 1 x Water).
        val saleA = insertSale(
            day = DAY_ONE, cashierId = cashier, subtotal = 8950, discounts = 200, tax = 1100,
            total = 10050, kind = "sale", receipt = "TILL1-0001",
        )
        insertLine(saleA, milo, qty = 2, unitPrice = 4500, discount = 200, tax = 1100, lineTotal = 9900)
        insertLine(saleA, water, qty = 1, unitPrice = 150, discount = 0, tax = 0, lineTotal = 150)

        // Day 1, ticket B: no line items at all — still one sale.
        insertSale(
            day = DAY_ONE, cashierId = cashier, subtotal = 500, discounts = 0, tax = 0,
            total = 500, kind = "sale", receipt = "TILL1-0002",
        )

        // Day 1, refund of ticket A: counted in refunds_total only.
        insertSale(
            day = DAY_ONE, cashierId = cashier, subtotal = 9900, discounts = 0, tax = 0,
            total = 9900, kind = "refund", receipt = "TILL1-0003", originalSaleId = saleA,
        )

        // Day 2, an ordinary ticket.
        insertSale(
            day = DAY_TWO, cashierId = cashier, subtotal = 1000, discounts = 0, tax = 0,
            total = 1000, kind = "sale", receipt = "TILL1-0004",
        )

        val rows = db.read { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT day, sales_count, revenue, discounts, tax, refunds_total, gross_profit
                    FROM dashboard_sales_daily ORDER BY day
                    """.trimIndent(),
                ).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                listOf(
                                    rs.getString("day"),
                                    rs.getLong("sales_count").toString(),
                                    rs.getLong("revenue").toString(),
                                    rs.getLong("discounts").toString(),
                                    rs.getLong("tax").toString(),
                                    rs.getLong("refunds_total").toString(),
                                    rs.getLong("gross_profit").toString(),
                                ),
                            )
                        }
                    }
                }
            }
        }

        // Sale A's money appears once (10050), not once per line (3 rows before
        // the fix: 10050 x 2 + 500 and sales_count = 4).
        assertEquals(
            listOf(
                listOf(DAY_ONE, "2", "10550", "200", "1100", "9900", "2850"),
                listOf(DAY_TWO, "1", "1000", "0", "0", "0", "0"),
            ),
            rows,
        )
    }

    // ------------------------------------------------------------------
    // Migration 0003: is_admin() semantics and its EXECUTE grant
    // ------------------------------------------------------------------
    @Test
    fun `is_admin recognizes only active admins`() {
        val activeAdmin = newAuthUser("Owner", "admin", active = true)
        val inactiveAdmin = newAuthUser("Former Owner", "admin", active = false)
        val cashier = newAuthUser("Ama", "cashier", active = true)

        assertTrue(isAdminAs(activeAdmin))
        assertFalse(isAdminAs(inactiveAdmin))
        assertFalse(isAdminAs(cashier))
        assertFalse(isAdminAs(null))
        // Unknown signed-in user: no profile row at all.
        assertFalse(isAdminAs(UUID.randomUUID()))
    }

    @Test
    fun `is_admin is executable by authenticated only`() {
        assertTrue(canExecute("authenticated"))
        assertTrue(canExecute("service_role"))
        assertFalse(canExecute("anon"))

        // ...and the anon denial is real, not just an ACL row.
        val failure = assertFailsWith<SQLException> {
            db.read { conn ->
                conn.createStatement().use { it.execute("SET ROLE anon") }
                try {
                    conn.createStatement().use { it.executeQuery("SELECT public.is_admin()").close() }
                } finally {
                    conn.createStatement().use { it.execute("RESET ROLE") }
                }
            }
        }
        assertTrue(
            failure.message.orEmpty().contains("permission denied"),
            "expected a permission error, got: ${failure.message}",
        )
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun canExecute(role: String): Boolean = db.read { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT has_function_privilege('$role', 'public.is_admin()', 'EXECUTE')",
            ).use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

    /** Calls public.is_admin() as the `authenticated` role for a JWT subject. */
    private fun isAdminAs(subject: UUID?): Boolean = db.read { conn ->
        conn.createStatement().use { it.execute("SET ROLE authenticated") }
        try {
            setJwtSubject(conn, subject)
            conn.createStatement().use { st ->
                st.executeQuery("SELECT public.is_admin()").use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
        } finally {
            conn.createStatement().use { it.execute("RESET ROLE") }
        }
    }

    private fun newAuthUser(fullName: String, role: String, active: Boolean): UUID {
        val id = UUID.randomUUID()
        db.transaction { conn ->
            conn.prepareStatement("INSERT INTO auth.users (id) VALUES (?)").use { st ->
                st.setObject(1, id)
                st.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO profiles (id, full_name, role, is_active) VALUES (?, ?, ?, ?)",
            ).use { st ->
                st.setObject(1, id)
                st.setString(2, fullName)
                st.setString(3, role)
                st.setBoolean(4, active)
                st.executeUpdate()
            }
        }
        return id
    }

    /** Sets the `request.jwt.claim.sub` the auth.uid() shim reads. */
    private fun setJwtSubject(conn: Connection, subject: UUID?) {
        conn.prepareStatement("SELECT set_config('request.jwt.claim.sub', ?, false)").use { st ->
            st.setString(1, subject?.toString() ?: "")
            st.executeQuery().close()
        }
    }

    // ------------------------------------------------------------------
    // Migration 0004: the 0002 RPCs run with an empty search_path
    // ------------------------------------------------------------------
    @Test
    fun `admin rpcs are pinned to an empty search_path with qualified objects`() {
        val defs = db.read { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT p.proname, pg_get_functiondef(p.oid)
                    FROM pg_proc p
                    JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'public'
                      AND p.proname IN ('admin_update_product_price', 'admin_set_product_category')
                    """.trimIndent(),
                ).use { rs ->
                    buildMap {
                        while (rs.next()) put(rs.getString(1), rs.getString(2))
                    }
                }
            }
        }

        assertEquals(setOf("admin_update_product_price", "admin_set_product_category"), defs.keys)
        for ((name, def) in defs) {
            assertTrue(
                def.lowercase().contains("set search_path to ''"),
                "$name must pin search_path to empty (got: ${def.lines().first { it.contains("search_path") }})",
            )
            for (unqualified in listOf("from products", "into public.products", "into product_price_audit",
                                       "into product_categories", "join product_categories")) {
                val offending = unqualified.removePrefix("public.")
                assertTrue(
                    !Regex("(?i)(from|join|update|into)\\s+$offending\\b").containsMatchIn(def),
                    "$name references $offending unqualified",
                )
            }
        }
        assertTrue(defs.getValue("admin_update_product_price").contains("public.product_price_audit"))
        assertTrue(defs.getValue("admin_set_product_category").contains("public.product_categories"))
    }

    @Test
    fun `admin rpcs write price audit and category, and refuse non-admins`() {
        val admin = newAuthUser("Owner", "admin", active = true)
        val cashierSubject = newAuthUser("Ama", "cashier", active = true)
        val productId = insertProduct("TV-001", "Television 43in", unitPrice = 100_000, costPrice = 80_000)

        // Happy path, through the real permission path (role + JWT subject).
        db.transaction { conn ->
            conn.createStatement().use { it.execute("SET ROLE authenticated") }
            try {
                setJwtSubject(conn, admin)
                conn.prepareStatement("SELECT public.admin_update_product_price(?, ?, ?)").use { st ->
                    st.setString(1, productId)
                    st.setLong(2, 105_000)
                    st.setString(3, "supplier increase")
                    st.execute()
                }
                conn.prepareStatement("SELECT public.admin_set_product_category(?, ?)").use { st ->
                    st.setString(1, productId)
                    st.setString(2, "  Electronics  ")
                    st.execute()
                }
            } finally {
                conn.createStatement().use { it.execute("RESET ROLE") }
            }
        }

        val priceAndCategory = db.read { conn ->
            conn.prepareStatement(
                """
                SELECT p.unit_price, c.category
                FROM products p
                LEFT JOIN product_categories c ON c.product_id = p.product_id
                WHERE p.product_id = ?
                """.trimIndent(),
            ).use { st ->
                st.setString(1, productId)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1).toString() to rs.getString(2)
                }
            }
        }
        assertEquals(105_000L.toString() to "Electronics", priceAndCategory)

        val audit = db.read { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT old_price, new_price, changed_by, reason FROM product_price_audit",
                ).use { rs ->
                    rs.next()
                    listOf(
                        rs.getLong("old_price").toString(),
                        rs.getLong("new_price").toString(),
                        rs.getString("changed_by"),
                        rs.getString("reason"),
                    )
                }
            }
        }
        assertEquals(listOf("100000", "105000", admin.toString(), "supplier increase"), audit)

        // A non-admin ctx is refused before anything is touched.
        val failure = assertFailsWith<SQLException> {
            // Autocommit reader: after the RPC refuses, the session must still
            // be usable for RESET ROLE (a transaction would be left aborted).
            db.read { conn ->
                conn.createStatement().use { it.execute("SET ROLE authenticated") }
                try {
                    setJwtSubject(conn, cashierSubject)
                    conn.prepareStatement("SELECT public.admin_update_product_price(?, ?, ?)").use { st ->
                        st.setString(1, productId)
                        st.setLong(2, 110_000)
                        st.setString(3, "unauthorized change")
                        st.execute()
                    }
                } finally {
                    conn.createStatement().use { it.execute("RESET ROLE") }
                }
            }
        }
        assertTrue(
            failure.message.orEmpty().contains("only administrators"),
            "expected the admin gate message, got: ${failure.message}",
        )
        val priceAfterRefusal = db.read { conn ->
            conn.prepareStatement(
                "SELECT unit_price FROM products WHERE product_id = ?",
            ).use { st ->
                st.setString(1, productId)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
        assertEquals(105_000L, priceAfterRefusal)
    }

    private fun insertProduct(sku: String, name: String, unitPrice: Long, costPrice: Long): String {
        val productId = UUID.randomUUID().toString()
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO products (product_id, device_id, sku, name, unit_price, cost_price,
                                      tax_rate_bp, low_stock_threshold, is_active, created_at, updated_at)
                VALUES (?, 'till-1', ?, ?, ?, ?, 0, 0, TRUE, ?, ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, productId)
                st.setString(2, sku)
                st.setString(3, name)
                st.setLong(4, unitPrice)
                st.setLong(5, costPrice)
                st.setObject(6, OffsetDateTime.parse(DAY_ONE_AT))
                st.setObject(7, OffsetDateTime.parse(DAY_ONE_AT))
                st.executeUpdate()
            }
        }
        return productId
    }

    private fun insertUser(fullName: String, role: String): String {
        val userId = UUID.randomUUID().toString()
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (user_id, device_id, full_name, role, mfa_enabled,
                                   failed_attempts, is_active, created_at, updated_at)
                VALUES (?, NULL, ?, ?, FALSE, 0, TRUE, ?, ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, userId)
                st.setString(2, fullName)
                st.setString(3, role)
                st.setObject(4, OffsetDateTime.parse(DAY_ONE_AT))
                st.setObject(5, OffsetDateTime.parse(DAY_ONE_AT))
                st.executeUpdate()
            }
        }
        return userId
    }

    private fun insertSale(
        day: String,
        cashierId: String,
        subtotal: Long,
        discounts: Long,
        tax: Long,
        total: Long,
        kind: String,
        receipt: String,
        originalSaleId: String? = null,
    ): String {
        val saleId = UUID.randomUUID().toString()
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO sales (sale_id, device_id, receipt_number, cashier_id, subtotal,
                                   discount_total, tax_total, total, kind, original_sale_id,
                                   status, sync_state, created_at)
                VALUES (?, 'till-1', ?, ?, ?, ?, ?, ?, ?, ?, 'completed', 'synced', ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, saleId)
                st.setString(2, receipt)
                st.setString(3, cashierId)
                st.setLong(4, subtotal)
                st.setLong(5, discounts)
                st.setLong(6, tax)
                st.setLong(7, total)
                st.setString(8, kind)
                st.setString(9, originalSaleId)
                st.setObject(10, OffsetDateTime.parse("${day}T12:00:00Z"))
                st.executeUpdate()
            }
        }
        return saleId
    }

    private fun insertLine(
        saleId: String,
        productId: String,
        qty: Long,
        unitPrice: Long,
        discount: Long,
        tax: Long,
        lineTotal: Long,
    ) {
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO sale_items (item_id, sale_id, product_id, sku, name, quantity,
                                        unit_price, discount, tax_rate_bp, tax, line_total,
                                        price_source)
                VALUES (?, ?, ?, 'SKU', 'Item', ?, ?, ?, 0, ?, ?, 'catalog')
                """.trimIndent(),
            ).use { st ->
                st.setString(1, UUID.randomUUID().toString())
                st.setString(2, saleId)
                st.setString(3, productId)
                st.setLong(4, qty)
                st.setLong(5, unitPrice)
                st.setLong(6, discount)
                st.setLong(7, tax)
                st.setLong(8, lineTotal)
                st.executeUpdate()
            }
        }
    }

    private companion object {
        const val DAY_ONE = "2026-10-26"
        const val DAY_TWO = "2026-10-27"
        const val DAY_ONE_AT = "2026-10-26T12:00:00Z"
    }
}

/**
 * Applies the Supabase migrations once per test JVM, after installing the small
 * slice of the Supabase environment they depend on (auth schema, JWT helper and
 * the three PostgREST roles).
 */
private object Migrations {
    @Volatile
    private var applied = false

    @Synchronized
    fun ensure(db: Database) {
        if (applied) return
        db.read { conn -> installSupabaseShim(conn) }
        // Canonical POS schema first — the migrations layer on top of it.
        Schema.apply(db)
        for (file in listOf("0001_admin_foundation.sql", "0002_product_management.sql",
                            "0003_dashboard_aggregation_and_authz.sql",
                            "0004_rpc_search_path_hardening.sql")) {
            val sql = Files.readString(migrationsDir().resolve(file))
            db.read { conn -> conn.createStatement().use { it.execute(sql) } }
        }
        applied = true
    }

    private fun migrationsDir(): Path {
        val candidates = listOf(
            Path.of("..", "supabase", "migrations"),
            Path.of("supabase", "migrations"),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("supabase/migrations not found from ${Path.of("").toAbsolutePath()}")
    }

    private fun installSupabaseShim(conn: Connection) {
        conn.createStatement().use { st ->
            for (role in listOf("anon", "authenticated", "service_role")) {
                st.execute(
                    "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$role') " +
                        "THEN CREATE ROLE $role NOLOGIN; END IF; END \$\$",
                )
            }
            st.execute("CREATE SCHEMA IF NOT EXISTS auth")
            st.execute("CREATE TABLE IF NOT EXISTS auth.users (id uuid PRIMARY KEY)")
            st.execute(
                """
                CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
                  SELECT NULLIF(current_setting('request.jwt.claim.sub', true), '')::uuid
                $$;
                """.trimIndent(),
            )
            st.execute("GRANT USAGE ON SCHEMA public TO authenticated, anon, service_role")
            // Supabase's default privileges: RLS decides rows, grants only decide reachability.
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO authenticated")
        }
    }
}
