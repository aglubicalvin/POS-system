package com.businessstore.pos

import com.businessstore.pos.db.Database
import com.businessstore.pos.db.Schema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 4.3 workflows against a real PostgreSQL: the canonical Kotlin schema with
 * migrations 0005–0007 applied on top, exactly as [SupabaseMigrations] does for the
 * admin layer.
 *
 * These are behavior tests, not file-parsing tests. Every assertion reads a row the
 * migration actually produced, so a migration that parses but does not execute fails
 * here. Nothing touches a hosted or production database — [TestDb] is an embedded
 * throwaway cluster.
 */
class Phase43WorkflowTest {

    private val db: Database get() = Database.from(TestDb.dataSource)

    private lateinit var admin: UUID
    private lateinit var cashier: UUID

    @BeforeEach
    fun reset() {
        Schema.apply(db)
        SupabaseMigrations.ensure(db)
        db.transaction { conn ->
            conn.createStatement().use {
                it.execute(
                    """
                    TRUNCATE auth.users, profiles, payments, sale_items, sales,
                             hire_purchase_payments, hire_purchase_contracts,
                             payroll_deductions, customers, product_price_audit,
                             product_categories, products, users, stock_on_hand,
                             audit_log
                    CASCADE
                    """.trimIndent(),
                )
            }
        }
        admin = newAuthUser("Owner", "admin", active = true)
        cashier = newAuthUser("Ama", "cashier", active = true)
    }

    // ------------------------------------------------------------------
    // The migrations themselves
    // ------------------------------------------------------------------

    @Test
    fun `migrations 0005 to 0007 create every object they advertise`() {
        val tables = queryStrings(
            "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
        )
        assertTrue(
            tables.containsAll(
                listOf("customers", "payroll_deductions", "hire_purchase_contracts", "hire_purchase_payments"),
            ),
            "missing admin tables; got $tables",
        )

        val views = queryStrings(
            "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_type = 'VIEW'",
        )
        assertTrue(
            views.containsAll(
                listOf(
                    "customer_purchase_summary",
                    "hire_purchase_overview",
                    "admin_product_catalog",
                    "admin_inventory_overview",
                ),
            ),
            "missing admin views; got $views",
        )

        val functions = queryStrings(
            """
            SELECT p.proname FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'public'
            """.trimIndent(),
        )
        assertTrue(
            functions.containsAll(
                listOf(
                    "admin_update_product_media",
                    "admin_create_hire_purchase_contract",
                    "admin_pay_hire_purchase_installment",
                    "admin_reconcile_mobile_money_payment",
                ),
            ),
            "missing admin functions; got $functions",
        )

        val indexes = queryStrings("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")
        assertTrue(
            indexes.containsAll(
                listOf(
                    "idx_payroll_deductions_month_status",
                    "idx_hire_purchase_status_end_date",
                    "idx_sales_customer_created",
                ),
            ),
            "missing migration indexes; got $indexes",
        )

        val rlsTables = queryStrings(
            """
            SELECT c.relname FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relrowsecurity
            """.trimIndent(),
        )
        assertTrue(
            rlsTables.containsAll(
                listOf("customers", "payroll_deductions", "hire_purchase_contracts", "hire_purchase_payments"),
            ),
            "admin tables must have RLS enabled; got $rlsTables",
        )

        val policies = queryStrings(
            "SELECT tablename || '.' || policyname FROM pg_policies WHERE schemaname = 'public'",
        )
        assertTrue(
            policies.containsAll(
                listOf(
                    "customers.customers_admin_all",
                    "payroll_deductions.payroll_deductions_admin_all",
                    "hire_purchase_contracts.hp_contracts_admin_all",
                    "hire_purchase_payments.hp_payments_admin_all",
                ),
            ),
            "missing admin policies; got $policies",
        )
    }

    @Test
    fun `migrations only add columns to the canonical POS tables`() {
        assertEquals(
            CANONICAL_PRODUCTS + listOf("description", "image_url", "thumbnail_url", "reorder_point"),
            columnsOf("products"),
        )
        assertEquals(
            CANONICAL_SALES + listOf("customer_id", "payment_channel", "staff_number"),
            columnsOf("sales"),
        )
        // The canonical unique sku index and the migration's customer index must both exist.
        val salesIndexes = queryStrings(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'sales'",
        )
        assertTrue(salesIndexes.contains("idx_sales_customer_created"))
        val productIndexes = queryStrings(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'products'",
        )
        assertTrue(productIndexes.contains("products_sku_key"))
    }

    // ------------------------------------------------------------------
    // Hire purchase: schedule, balance, tax, sale totals
    // ------------------------------------------------------------------

    @Test
    fun `hire purchase writes one consistent sale, contract and installment plan`() {
        val productId = insertProduct(
            sku = "MILO-400",
            name = "Milo Refill 400g",
            unitPrice = 5000,
            costPrice = 3000,
            taxBp = 1250,
        )
        insertUser("Ama", "cashier")
        val customerId = insertCustomer("EMP-0042", "Akosua Mensah")

        val contractId = createHirePurchase(
            customerId = customerId,
            productId = productId,
            quantity = 2,
            retailPrice = 5000,
            deposit = 2000,
            months = 6,
            startDate = "2026-11-01",
        )

        // 2 x GHS 50.00 = 10000 net, 12.5% tax = 1250, total 11250.
        val sale = rowStrings(
            """
            SELECT s.subtotal, s.tax_total, s.total, s.kind, s.status,
                   s.payment_channel, s.staff_number
            FROM sales s
            JOIN hire_purchase_contracts hpc ON hpc.sale_id = s.sale_id
            WHERE hpc.contract_id = ?::uuid
            """.trimIndent(),
            contractId,
        )
        assertEquals(listOf("10000", "1250", "11250", "sale", "completed", "payroll_deduction", "EMP-0042"), sale)

        assertEquals(
            listOf("2", "5000", "1250", "11250"),
            rowStrings(
                """
                SELECT i.quantity, i.unit_price, i.tax, i.line_total
                FROM sale_items i
                JOIN hire_purchase_contracts hpc ON hpc.sale_id = i.sale_id
                WHERE hpc.contract_id = ?::uuid
                """.trimIndent(),
                contractId,
            ),
        )

        // Deposit 2000 of a 11250 contract leaves 9250 to finance over six months.
        val contract = rowStrings(
            """
            SELECT total_amount, remaining_balance, installment_amount, deposit,
                   duration_months, status, start_date, end_date
            FROM hire_purchase_contracts WHERE contract_id = ?::uuid
            """.trimIndent(),
            contractId,
        )
        assertEquals(listOf("11250", "9250", "1541", "2000", "6", "active", "2026-11-01", "2027-05-01"), contract)

        // Integer division leaves a 4-pesewa remainder that the last installment absorbs,
        // so the schedule sums to exactly the financed balance.
        val installments = queryLongs(
            "SELECT amount FROM hire_purchase_payments WHERE contract_id = ?::uuid ORDER BY due_date",
            contractId,
        )
        assertEquals(listOf(1541L, 1541L, 1541L, 1541L, 1541L, 1545L), installments)
        assertEquals(9250L, installments.sum())

        assertEquals(
            listOf("2026-12-01", "2027-05-01"),
            rowStrings(
                """
                SELECT min(due_date)::text, max(due_date)::text
                FROM hire_purchase_payments WHERE contract_id = ?::uuid
                """.trimIndent(),
                contractId,
            ),
        )

        // The deposit is a real confirmed payment, keyed so a retry cannot duplicate it.
        assertEquals(
            listOf("cash", "2000", "confirmed", "hp-deposit:$contractId"),
            rowStrings(
                """
                SELECT p.method, p.amount, p.status, p.idempotency_key
                FROM payments p
                JOIN hire_purchase_contracts hpc ON hpc.sale_id = p.sale_id
                WHERE hpc.contract_id = ?::uuid
                """.trimIndent(),
                contractId,
            ),
        )

        assertEquals(
            listOf("9250", "2026-11", "pending"),
            rowStrings(
                """
                SELECT pd.amount, pd.deduction_month, pd.status
                FROM payroll_deductions pd
                JOIN hire_purchase_contracts hpc ON hpc.sale_id = pd.sale_id
                WHERE hpc.contract_id = ?::uuid
                """.trimIndent(),
                contractId,
            ),
        )

        // Known gap, pinned so a change is deliberate: a hire-purchase sale is
        // recorded server-side without a stock movement, so inventory totals do not
        // reflect goods sold on contract yet. Tracked in docs/roadmap.md.
        assertEquals(
            listOf("0"),
            queryStrings("SELECT count(*) FROM stock_movements WHERE product_id = ?", productId),
        )
    }

    // ------------------------------------------------------------------
    // Authorization: RLS, grants and financial guards
    // ------------------------------------------------------------------

    @Test
    fun `RLS hides protected rows from non-admins and shows them to admins`() {
        val cashierId = insertUser("Ama", "cashier")
        val saleId = insertSale(cashierId, null, total = 5000)
        insertPayment(saleId, method = "mobile_money", amount = 5000, providerRef = "MM-RLS")
        val customerId = insertCustomer("EMP-9000", "Abena Owusu")
        insertDeduction(customerId, amount = 500, month = "2026-11", status = "pending")

        val tables = listOf("sales", "payments", "customers", "payroll_deductions")
        assertEquals(
            listOf(0L, 0L, 0L, 0L),
            tables.map { countAs(cashier, it) },
            "a non-admin must not read protected rows",
        )
        assertEquals(listOf(1L, 1L, 1L, 1L), tables.map { countAs(admin, it) })
    }

    @Test
    fun `admin views expose nothing to a non-admin`() {
        val cashierId = insertUser("Ama", "cashier")
        insertSale(cashierId, null, total = 5000)
        insertProduct("VIEW-1", "View Test", 1000, 500, taxBp = 0)
        insertCustomer("EMP-9100", "Efua Mensah")

        val views = listOf(
            "dashboard_sales_daily",
            "admin_product_catalog",
            "admin_inventory_overview",
            "customer_purchase_summary",
            "hire_purchase_overview",
        )
        for (view in views) {
            assertEquals(0L, countAs(cashier, view), "$view leaked rows to a non-admin")
        }
        // The same views are usable for an admin, so the zero above is the policy.
        assertTrue(countAs(admin, "dashboard_sales_daily") > 0)
        assertTrue(countAs(admin, "admin_product_catalog") > 0)
    }

    @Test
    fun `authenticated clients cannot write financial tables directly`() {
        val cashierId = insertUser("Ama", "cashier")
        val saleId = insertSale(cashierId, null, total = 5000)
        val paymentId = insertPayment(saleId, "mobile_money", 5000, "MM-DIRECT")

        // Not even an administrator may forge a reconciliation through PostgREST:
        // the payments policy is SELECT-only, so reconciliation stays RPC-only.
        val adminUpdate = db.read { conn ->
            conn.createStatement().use { it.execute("SET ROLE authenticated") }
            try {
                setJwtSubject(conn, admin)
                conn.prepareStatement("UPDATE payments SET reconciled_at = now() WHERE payment_id = ?").use { st ->
                    st.setString(1, paymentId)
                    st.executeUpdate()
                }
            } finally {
                conn.createStatement().use { it.execute("RESET ROLE") }
            }
        }
        assertEquals(0, adminUpdate)
        assertEquals(listOf("0"), queryStrings("SELECT count(*) FROM payments WHERE reconciled_at IS NOT NULL"))

        // A cashier cannot insert a customer or rewrite a payment.
        val customerInsert = assertFailsWith<SQLException> {
            db.read { conn ->
                conn.createStatement().use { it.execute("SET ROLE authenticated") }
                try {
                    setJwtSubject(conn, cashier)
                    conn.prepareStatement(
                        "INSERT INTO customers (staff_number, full_name) VALUES ('EMP-X', 'Forged')",
                    ).use { it.executeUpdate() }
                } finally {
                    conn.createStatement().use { it.execute("RESET ROLE") }
                }
            }
        }
        assertTrue(
            customerInsert.message.orEmpty().contains("row-level security"),
            customerInsert.message,
        )

        val cashierUpdate = db.read { conn ->
            conn.createStatement().use { it.execute("SET ROLE authenticated") }
            try {
                setJwtSubject(conn, cashier)
                conn.prepareStatement("UPDATE payments SET amount = 1 WHERE payment_id = ?").use { st ->
                    st.setString(1, paymentId)
                    st.executeUpdate()
                }
            } finally {
                conn.createStatement().use { it.execute("RESET ROLE") }
            }
        }
        assertEquals(0, cashierUpdate)
        assertEquals(
            listOf("5000"),
            queryStrings("SELECT amount FROM payments WHERE payment_id = ?", paymentId),
        )
    }

    @Test
    fun `anonymous clients cannot reach the admin surface`() {
        val functions = listOf(
            "public.admin_update_product_price(text, bigint, text)",
            "public.admin_set_product_category(text, text)",
            "public.admin_update_product_media(text, text, text, text)",
            "public.admin_create_hire_purchase_contract(uuid, text, bigint, bigint, bigint, integer, date)",
            "public.admin_pay_hire_purchase_installment(uuid)",
            "public.admin_reconcile_mobile_money_payment(text, text, bigint)",
        )
        for (function in functions) {
            assertFalse(
                hasPrivilege("anon", function, "EXECUTE"),
                "anon must not be able to execute $function",
            )
            assertTrue(
                hasPrivilege("authenticated", function, "EXECUTE"),
                "authenticated must be able to execute $function (it gates on is_admin internally)",
            )
        }

        val failure = assertFailsWith<SQLException> {
            db.read { conn ->
                conn.createStatement().use { it.execute("SET ROLE anon") }
                try {
                    conn.createStatement().use { it.executeQuery("SELECT count(*) FROM payments").close() }
                } finally {
                    conn.createStatement().use { it.execute("RESET ROLE") }
                }
            }
        }
        assertTrue(failure.message.orEmpty().contains("permission denied"), failure.message)
    }

    @Test
    fun `an installment larger than the remaining balance is refused`() {
        val productId = insertProduct("GUARD-1", "Guarded Item", 1000, 500, taxBp = 0)
        insertUser("Ama", "cashier")
        val customerId = insertCustomer("EMP-9200", "Yaa Asantewaa")
        // 1000 over 2 months, no deposit: 500 + 500.
        val contractId = createHirePurchase(customerId, productId, 1, 1000, 0, 2, "2026-11-01")

        val first = queryValues(
            "SELECT hp_payment_id FROM hire_purchase_payments WHERE contract_id = ?::uuid ORDER BY due_date LIMIT 1",
            contractId,
        ).single()
        // Corrupt one installment so it exceeds the outstanding balance.
        db.transaction { conn ->
            conn.prepareStatement("UPDATE hire_purchase_payments SET amount = 1500 WHERE hp_payment_id = ?::uuid").use { st ->
                st.setString(1, first)
                st.executeUpdate()
            }
        }

        val refused = assertFailsWith<SQLException> { payInstallment(first) }
        assertTrue(
            refused.message.orEmpty().contains("exceeds remaining contract balance"),
            refused.message,
        )
        assertEquals(
            listOf("1000", "active"),
            rowStrings(
                "SELECT remaining_balance, status FROM hire_purchase_contracts WHERE contract_id = ?::uuid",
                contractId,
            ),
        )
        assertEquals(
            listOf("0"),
            queryStrings(
                "SELECT count(*) FROM payments p JOIN hire_purchase_contracts c ON c.sale_id = p.sale_id " +
                    "WHERE c.contract_id = ?::uuid AND p.method = 'credit'",
                contractId,
            ),
        )
    }

    @Test
    fun `hire-purchase money stays internally consistent`() {
        val productId = insertProduct("INV-1", "Invariant Item", 3333, 2000, taxBp = 750)
        insertUser("Ama", "cashier")
        val customerId = insertCustomer("EMP-9300", "Akua Badu")
        val contractId = createHirePurchase(customerId, productId, 3, 3333, 1500, 7, "2026-11-01")

        val sale = rowStrings(
            """
            SELECT subtotal, discount_total, tax_total, total
            FROM sales WHERE sale_id = (SELECT sale_id FROM hire_purchase_contracts WHERE contract_id = ?::uuid)
            """.trimIndent(),
            contractId,
        ).map { it!!.toLong() }
        assertEquals(sale[0] - sale[1] + sale[2], sale[3], "subtotal - discount + tax must equal the total")

        val contract = rowStrings(
            "SELECT total_amount, deposit, remaining_balance FROM hire_purchase_contracts WHERE contract_id = ?::uuid",
            contractId,
        ).map { it!!.toLong() }
        assertEquals(contract[0], sale[3], "the contract total must equal the sale total")
        assertEquals(contract[0], contract[1] + contract[2], "deposit + financed must equal the total")
        assertTrue(contract[2] >= 0, "remaining balance must never be negative")

        assertEquals(
            contract[2],
            queryLongs("SELECT amount FROM hire_purchase_payments WHERE contract_id = ?::uuid", contractId).sum(),
            "the installment schedule must sum to the financed balance",
        )
        assertEquals(
            contract[2],
            queryLongs(
                "SELECT amount FROM payroll_deductions WHERE sale_id = (SELECT sale_id FROM hire_purchase_contracts WHERE contract_id = ?::uuid)",
                contractId,
            ).single(),
            "the payroll deduction must equal the financed balance",
        )
    }

    @Test
    fun `paying installments reduces the balance once and never twice`() {
        val productId = insertProduct("KETTLE", "Electric Kettle", 5000, 3000, taxBp = 1250)
        insertUser("Ama", "cashier")
        val customerId = insertCustomer("EMP-0042", "Akosua Mensah")
        val contractId = createHirePurchase(customerId, productId, 2, 5000, 2000, 6, "2026-11-01")

        val first = queryValues(
            "SELECT hp_payment_id FROM hire_purchase_payments WHERE contract_id = ?::uuid ORDER BY due_date LIMIT 1",
            contractId,
        ).single()
        payInstallment(first)

        assertEquals(
            listOf("7709"),
            queryStrings(
                "SELECT remaining_balance FROM hire_purchase_contracts WHERE contract_id = ?::uuid",
                contractId,
            ),
        )
        assertEquals(7709L, queryLongs(
            "SELECT amount FROM payroll_deductions WHERE sale_id = (SELECT sale_id FROM hire_purchase_contracts WHERE contract_id = ?::uuid)",
            contractId,
        ).single())

        assertEquals(
            listOf("confirmed", "1541", "credit", "hp-installment:$first"),
            rowStrings(
                """
                SELECT p.status, p.amount, p.method, p.idempotency_key
                FROM payments p
                JOIN hire_purchase_contracts hpc ON hpc.sale_id = p.sale_id
                WHERE hpc.contract_id = ?::uuid AND p.method = 'credit'
                """.trimIndent(),
                contractId,
            ),
        )

        // A retry of the same installment is a no-op: no second payment row, no second debit.
        payInstallment(first)
        assertEquals(
            listOf("2"),
            queryStrings(
                """
                SELECT count(*) FROM payments p
                JOIN hire_purchase_contracts hpc ON hpc.sale_id = p.sale_id
                WHERE hpc.contract_id = ?::uuid
                """.trimIndent(),
                contractId,
            ),
        )
        assertEquals(
            listOf("7709"),
            queryStrings(
                "SELECT remaining_balance FROM hire_purchase_contracts WHERE contract_id = ?::uuid",
                contractId,
            ),
        )

        // Pay everything off: the contract completes and the financed deduction is settled.
        queryValues(
            "SELECT hp_payment_id FROM hire_purchase_payments WHERE contract_id = ?::uuid AND status <> 'paid' ORDER BY due_date",
            contractId,
        ).forEach { payInstallment(it) }

        assertEquals(
            listOf("0", "completed", "0", "paid"),
            rowStrings(
                """
                SELECT c.remaining_balance, c.status,
                       (SELECT amount FROM payroll_deductions pd WHERE pd.sale_id = c.sale_id),
                       (SELECT status FROM payroll_deductions pd WHERE pd.sale_id = c.sale_id)
                FROM hire_purchase_contracts c WHERE c.contract_id = ?::uuid
                """.trimIndent(),
                contractId,
            ),
        )
        assertEquals(
            listOf("0"),
            queryStrings(
                "SELECT count(*) FROM hire_purchase_payments WHERE contract_id = ?::uuid AND status <> 'paid'",
                contractId,
            ),
        )
    }

    @Test
    fun `a refused contract leaves no partial records`() {
        val productId = insertProduct("TV-001", "Television 43in", 100_000, 80_000, taxBp = 0)
        insertUser("Ama", "cashier")
        val customerId = insertCustomer("EMP-0007", "Yaw Boateng")

        // Deposit above the contract total is rejected by the function itself.
        val failure = assertFailsWith<SQLException> {
            createHirePurchase(customerId, productId, 1, 100_000, 200_000, 6, "2026-11-01")
        }
        assertTrue(
            failure.message.orEmpty().contains("deposit cannot exceed"),
            "expected the deposit guard, got: ${failure.message}",
        )

        // Duration zero is rejected before any row is written.
        val badDuration = assertFailsWith<SQLException> {
            createHirePurchase(customerId, productId, 1, 100_000, 0, 0, "2026-11-01")
        }
        assertTrue(badDuration.message.orEmpty().contains("must be positive"))

        assertEquals(
            listOf("0", "0", "0", "0"),
            rowStrings(
                """
                SELECT (SELECT count(*) FROM hire_purchase_contracts),
                       (SELECT count(*) FROM hire_purchase_payments),
                       (SELECT count(*) FROM sales),
                       (SELECT count(*) FROM payments)
                """.trimIndent(),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Payroll and customer records
    // ------------------------------------------------------------------

    @Test
    fun `customer summary aggregates sales and deductions without multiplying them`() {
        val customerId = insertCustomer("EMP-0101", "Akosua Mensah")
        val cashierId = insertUser("Ama", "cashier")

        // Two sales and three deductions on one customer: a naive join would
        // multiply each side and report 2x the money.
        insertSale(cashierId, customerId, total = 3000)
        insertSale(cashierId, customerId, total = 5000)
        insertDeduction(customerId, amount = 1000, month = "2026-10", status = "pending")
        insertDeduction(customerId, amount = 500, month = "2026-10", status = "cancelled")
        insertDeduction(customerId, amount = 250, month = "2026-11", status = "processed")

        assertEquals(
            listOf("2", "8000", "1250", "EMP-0101"),
            rowStrings(
                """
                SELECT total_purchases, total_spent, total_payroll_deductions, staff_number
                FROM customer_purchase_summary WHERE customer_id = ?::uuid
                """.trimIndent(),
                customerId,
            ),
        )
    }

    @Test
    fun `customer records enforce staff-number and channel rules`() {
        val customerId = insertCustomer("EMP-0200", "Adwoa Serwaa")

        val duplicate = assertFailsWith<SQLException> {
            insertCustomer("EMP-0200", "Someone Else")
        }
        assertTrue(duplicate.message.orEmpty().contains("duplicate key"), duplicate.message)

        val badChannel = assertFailsWith<SQLException> {
            insertCustomer("EMP-0201", "Bad Channel", channel = "cheque")
        }
        assertTrue(badChannel.message.orEmpty().contains("check constraint"), badChannel.message)

        // Deactivating is a flag, not a delete: history stays queryable.
        db.transaction { conn ->
            conn.prepareStatement("UPDATE customers SET is_active = FALSE WHERE customer_id = ?::uuid").use { st ->
                st.setString(1, customerId)
                st.executeUpdate()
            }
        }
        assertEquals(
            listOf("Adwoa Serwaa", "false"),
            rowStrings("SELECT full_name, is_active FROM customers WHERE customer_id = ?::uuid", customerId),
        )

        // Deleting a customer that owns a contract is refused (on delete restrict).
        db.transaction { conn ->
            conn.prepareStatement("UPDATE customers SET is_active = TRUE WHERE customer_id = ?::uuid").use { st ->
                st.setString(1, customerId)
                st.executeUpdate()
            }
        }
        val productId = insertProduct("FAN-1", "Standing Fan", 20_000, 15_000, taxBp = 0)
        insertUser("Ama", "cashier")
        createHirePurchase(customerId, productId, 1, 20_000, 0, 3, "2026-11-01")
        val restricted = assertFailsWith<SQLException> {
            db.transaction { conn ->
                conn.prepareStatement("DELETE FROM customers WHERE customer_id = ?::uuid").use { st ->
                    st.setString(1, customerId)
                    st.executeUpdate()
                }
            }
        }
        assertTrue(restricted.message.orEmpty().contains("violates foreign key"), restricted.message)
    }

    // ------------------------------------------------------------------
    // Inventory reporting and category-only edits
    // ------------------------------------------------------------------

    @Test
    fun `inventory overview values stock and flags reorder points`() {
        val low = insertProduct("LOW-1", "Low Stock", 1000, 300, lowStock = 5, reorderPoint = 10)
        setStock(low, 2)
        val healthy = insertProduct("OK-1", "Healthy Stock", 1000, 300, lowStock = 5, reorderPoint = 10)
        setStock(healthy, 50)
        val retired = insertProduct("OLD-1", "Retired", 1000, 300, isActive = false, reorderPoint = 10)
        setStock(retired, 1)

        assertEquals(
            listOf("2", "600", "true", "10"),
            rowStrings(
                "SELECT quantity, inventory_value, low_stock, reorder_point " +
                    "FROM admin_inventory_overview WHERE product_id = ?",
                low,
            ),
        )
        assertEquals(
            listOf("50", "15000", "false"),
            rowStrings(
                "SELECT quantity, inventory_value, low_stock FROM admin_inventory_overview WHERE product_id = ?",
                healthy,
            ),
        )
        // Inactive products are not part of the admin inventory listing.
        assertEquals(
            listOf("0"),
            queryStrings("SELECT count(*) FROM admin_inventory_overview WHERE product_id = ?", retired),
        )
    }

    @Test
    fun `a category-only edit changes the category and nothing else`() {
        val productId = insertProduct("RADIO-1", "Radio", unitPrice = 12_000, costPrice = 9_000)

        asAdmin { conn ->
            conn.prepareStatement("SELECT public.admin_set_product_category(?, ?)").use { st ->
                st.setString(1, productId)
                st.setString(2, "  Electronics  ")
                st.execute()
            }
        }

        assertEquals(
            listOf("Electronics", "12000"),
            rowStrings(
                """
                SELECT pc.category, p.unit_price
                FROM products p LEFT JOIN product_categories pc ON pc.product_id = p.product_id
                WHERE p.product_id = ?
                """.trimIndent(),
                productId,
            ),
        )
        // No price change means no price audit entry to explain.
        assertEquals(listOf("0"), queryStrings("SELECT count(*) FROM product_price_audit"))
    }

    // ------------------------------------------------------------------
    // Mobile-money reconciliation
    // ------------------------------------------------------------------

    @Test
    fun `reconciliation matches a confirmed payment and is idempotent`() {
        val cashierId = insertUser("Ama", "cashier")
        val saleId = insertSale(cashierId, null, total = 5000)
        val paymentId = insertPayment(saleId, method = "mobile_money", amount = 5000, providerRef = "MM-REF-1")

        assertEquals(listOf(paymentId, "true", "matched"), reconcile(paymentId, "MM-REF-1", 5000))
        // A match must stamp reconciled_at (queryValues fails the test on a NULL).
        val firstReconciledAt = queryValues(
            "SELECT reconciled_at::text FROM payments WHERE payment_id = ?",
            paymentId,
        ).single()

        // Re-running the same reconciliation must not change the timestamp or duplicate rows.
        assertEquals(listOf(paymentId, "true", "matched"), reconcile(paymentId, "MM-REF-1", 5000))
        assertEquals(
            firstReconciledAt,
            queryValues("SELECT reconciled_at::text FROM payments WHERE payment_id = ?", paymentId).single(),
        )
        assertEquals(
            listOf("1"),
            queryStrings("SELECT count(*) FROM payments WHERE payment_id = ?", paymentId),
        )
    }

    @Test
    fun `reconciliation refuses mismatches without touching the payment`() {
        val cashierId = insertUser("Ama", "cashier")
        val saleId = insertSale(cashierId, null, total = 5000)
        val paymentId = insertPayment(saleId, method = "mobile_money", amount = 5000, providerRef = "MM-REF-2")
        val pendingId = insertPayment(
            saleId, method = "mobile_money", amount = 700, providerRef = "MM-REF-3", status = "pending",
        )
        val cashPayment = insertPayment(saleId, method = "cash", amount = 900, providerRef = null)

        assertEquals(listOf(paymentId, "false", "amount-mismatch"), reconcile(paymentId, "MM-REF-2", 4000))
        assertEquals(listOf(paymentId, "false", "reference-mismatch"), reconcile(paymentId, "MM-OTHER", 5000))
        assertEquals(listOf(pendingId, "false", "not-confirmed"), reconcile(pendingId, "MM-REF-3", 700))

        // Nothing was stamped by the failed attempts.
        assertEquals(
            listOf("0"),
            queryStrings("SELECT count(*) FROM payments WHERE reconciled_at IS NOT NULL"),
        )

        val cashFailure = assertFailsWith<SQLException> { reconcile(cashPayment, "MM-REF-4", 900) }
        assertTrue(cashFailure.message.orEmpty().contains("only mobile-money"))

        val unknown = assertFailsWith<SQLException> {
            reconcile(UUID.randomUUID().toString(), "MM-REF-5", 100)
        }
        assertTrue(unknown.message.orEmpty().contains("does not exist"))

        // A cashier (authenticated but not admin) cannot reconcile at all.
        val refused = assertFailsWith<SQLException> {
            db.read { conn ->
                conn.createStatement().use { it.execute("SET ROLE authenticated") }
                try {
                    setJwtSubject(conn, cashier)
                    conn.prepareStatement("SELECT * FROM public.admin_reconcile_mobile_money_payment(?, ?, ?)").use { st ->
                        st.setString(1, paymentId)
                        st.setString(2, "MM-REF-2")
                        st.setLong(3, 5000)
                        st.executeQuery().close()
                    }
                } finally {
                    conn.createStatement().use { it.execute("RESET ROLE") }
                }
            }
        }
        assertTrue(refused.message.orEmpty().contains("only administrators"), refused.message)

        // The provider reference and amount are still what the provider said.
        assertEquals(
            listOf("5000", "MM-REF-2", "confirmed"),
            rowStrings(
                "SELECT amount, provider_ref, status FROM payments WHERE payment_id = ?",
                paymentId,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Supabase session helpers
    // ------------------------------------------------------------------

    private fun <T> asAdmin(block: (Connection) -> T): T = db.read { conn ->
        conn.createStatement().use { it.execute("SET ROLE authenticated") }
        try {
            setJwtSubject(conn, admin)
            block(conn)
        } finally {
            conn.createStatement().use { it.execute("RESET ROLE") }
        }
    }

    private fun createHirePurchase(
        customerId: String,
        productId: String,
        quantity: Long,
        retailPrice: Long,
        deposit: Long,
        months: Int,
        startDate: String,
    ): String = asAdmin { conn ->
        conn.prepareStatement(
            "SELECT public.admin_create_hire_purchase_contract(?, ?, ?, ?, ?, ?, ?::date)",
        ).use { st ->
            st.setObject(1, UUID.fromString(customerId))
            st.setString(2, productId)
            st.setLong(3, quantity)
            st.setLong(4, retailPrice)
            st.setLong(5, deposit)
            st.setInt(6, months)
            st.setString(7, startDate)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }

    private fun payInstallment(paymentId: String) {
        asAdmin { conn ->
            conn.prepareStatement("SELECT public.admin_pay_hire_purchase_installment(?::uuid)").use { st ->
                st.setString(1, paymentId)
                st.execute()
            }
        }
    }

    private fun reconcile(paymentId: String, providerRef: String, amount: Long): List<String?> = asAdmin { conn ->
        conn.prepareStatement(
            "SELECT payment_id, matched, reason FROM public.admin_reconcile_mobile_money_payment(?, ?, ?)",
        ).use { st ->
            st.setString(1, paymentId)
            st.setString(2, providerRef)
            st.setLong(3, amount)
            st.executeQuery().use { rs ->
                rs.next()
                listOf(render(rs.getObject(1)), render(rs.getObject(2)), render(rs.getObject(3)))
            }
        }
    }

    private fun setJwtSubject(conn: Connection, subject: UUID?) {
        conn.prepareStatement("SELECT set_config('request.jwt.claim.sub', ?, false)").use { st ->
            st.setString(1, subject?.toString() ?: "")
            st.executeQuery().close()
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

    // ------------------------------------------------------------------
    // Fixtures and small queries
    // ------------------------------------------------------------------

    /** Row count visible to a Supabase role with a given JWT subject. */
    private fun countAs(subject: UUID, relation: String): Long = db.read { conn ->
        conn.createStatement().use { it.execute("SET ROLE authenticated") }
        try {
            setJwtSubject(conn, subject)
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM $relation").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        } finally {
            conn.createStatement().use { it.execute("RESET ROLE") }
        }
    }

    private fun hasPrivilege(role: String, function: String, privilege: String): Boolean = db.read { conn ->
        conn.prepareStatement("SELECT has_function_privilege(?, ?, ?)").use { st ->
            st.setString(1, role)
            st.setString(2, function)
            st.setString(3, privilege)
            st.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1)
            }
        }
    }

    private fun columnsOf(table: String): List<String> = queryValues(
        """
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = ?
        ORDER BY ordinal_position
        """.trimIndent(),
        table,
    )

    private fun queryStrings(sql: String, vararg params: Any?): List<String?> = db.read { conn ->
        conn.prepareStatement(sql).use { st ->
            params.forEachIndexed { index, value -> st.setObject(index + 1, value) }
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(render(rs.getObject(1))) }
            }
        }
    }

    /**
     * JDBC renders a SQL boolean as "t"/"f" through getString; assertions read
     * clearer (and match the admin app's JSON) when it is "true"/"false".
     */
    private fun render(value: Any?): String? = when (value) {
        null -> null
        is Boolean -> value.toString()
        else -> value.toString()
    }

    /** Like [queryStrings], but for call sites that need a non-null id or name. */
    private fun queryValues(sql: String, vararg params: Any?): List<String> =
        queryStrings(sql, *params).map { it ?: error("unexpected NULL from: $sql") }

    private fun queryLongs(sql: String, vararg params: Any?): List<Long> = db.read { conn ->
        conn.prepareStatement(sql).use { st ->
            params.forEachIndexed { index, value -> st.setObject(index + 1, value) }
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getLong(1)) }
            }
        }
    }

    private fun rowStrings(sql: String, vararg params: Any?): List<String?> = db.read { conn ->
        conn.prepareStatement(sql).use { st ->
            params.forEachIndexed { index, value -> st.setObject(index + 1, value) }
            st.executeQuery().use { rs ->
                check(rs.next()) { "expected one row from: $sql" }
                (1..rs.metaData.columnCount).map { render(rs.getObject(it)) }
            }
        }
    }

    private fun insertProduct(
        sku: String,
        name: String,
        unitPrice: Long,
        costPrice: Long,
        taxBp: Long = 0,
        lowStock: Long = 0,
        reorderPoint: Long = 0,
        isActive: Boolean = true,
    ): String {
        val productId = UUID.randomUUID().toString()
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO products (product_id, device_id, sku, name, unit_price, cost_price,
                                      tax_rate_bp, low_stock_threshold, is_active, created_at, updated_at)
                VALUES (?, 'till-1', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, productId)
                st.setString(2, sku)
                st.setString(3, name)
                st.setLong(4, unitPrice)
                st.setLong(5, costPrice)
                st.setLong(6, taxBp)
                st.setLong(7, lowStock)
                st.setBoolean(8, isActive)
                st.setObject(9, NOW)
                st.setObject(10, NOW)
                st.executeUpdate()
            }
            conn.prepareStatement("UPDATE products SET reorder_point = ? WHERE product_id = ?").use { st ->
                st.setLong(1, reorderPoint)
                st.setString(2, productId)
                st.executeUpdate()
            }
        }
        return productId
    }

    private fun setStock(productId: String, quantity: Long) {
        db.transaction { conn ->
            conn.prepareStatement(
                "INSERT INTO stock_on_hand (product_id, quantity, updated_at) VALUES (?, ?, ?)",
            ).use { st ->
                st.setString(1, productId)
                st.setLong(2, quantity)
                st.setObject(3, NOW)
                st.executeUpdate()
            }
        }
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
                st.setObject(4, NOW)
                st.setObject(5, NOW)
                st.executeUpdate()
            }
        }
        return userId
    }

    private fun insertCustomer(
        staffNumber: String,
        fullName: String,
        channel: String = "payroll_deduction",
    ): String {
        val customerId = UUID.randomUUID().toString()
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO customers (customer_id, staff_number, full_name, company,
                                       payment_method_preference)
                VALUES (?::uuid, ?, ?, 'Accra Retail Ltd', ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, customerId)
                st.setString(2, staffNumber)
                st.setString(3, fullName)
                st.setString(4, channel)
                st.executeUpdate()
            }
        }
        return customerId
    }

    private fun insertSale(cashierId: String, customerId: String?, total: Long): String {
        val saleId = UUID.randomUUID().toString()
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO sales (sale_id, device_id, receipt_number, cashier_id, currency,
                                   subtotal, discount_total, tax_total, total, kind, status,
                                   sync_state, created_at, customer_id, payment_channel)
                VALUES (?, 'till-1', ?, ?, 'GHS', ?, 0, 0, ?, 'sale', 'completed', 'synced', ?, ?::uuid, 'cash')
                """.trimIndent(),
            ).use { st ->
                st.setString(1, saleId)
                st.setString(2, "TILL1-" + saleId.take(8))
                st.setString(3, cashierId)
                st.setLong(4, total)
                st.setLong(5, total)
                st.setObject(6, NOW)
                st.setString(7, customerId)
                st.executeUpdate()
            }
        }
        return saleId
    }

    private fun insertDeduction(customerId: String, amount: Long, month: String, status: String) {
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO payroll_deductions (customer_id, sale_id, amount, deduction_month, status)
                VALUES (?::uuid, NULL, ?, ?, ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, customerId)
                st.setLong(2, amount)
                st.setString(3, month)
                st.setString(4, status)
                st.executeUpdate()
            }
        }
    }

    private fun insertPayment(
        saleId: String,
        method: String,
        amount: Long,
        providerRef: String?,
        status: String = "confirmed",
    ): String {
        val paymentId = UUID.randomUUID().toString()
        db.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO payments (payment_id, sale_id, method, amount, status,
                                      idempotency_key, provider, provider_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'mtn-momo', ?, ?, ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, paymentId)
                st.setString(2, saleId)
                st.setString(3, method)
                st.setLong(4, amount)
                st.setString(5, status)
                st.setString(6, "test:$paymentId")
                st.setString(7, providerRef)
                st.setObject(8, NOW)
                st.setObject(9, NOW)
                st.executeUpdate()
            }
        }
        return paymentId
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-11-01T09:00:00Z")

        /** `products` as defined by the Kotlin server's schema.sql, before any migration. */
        val CANONICAL_PRODUCTS = listOf(
            "product_id", "device_id", "sku", "name", "unit_price", "cost_price",
            "tax_rate_bp", "low_stock_threshold", "is_active", "version",
            "created_at", "updated_at",
        )

        /** `sales` as defined by the Kotlin server's schema.sql, before any migration. */
        val CANONICAL_SALES = listOf(
            "sale_id", "device_id", "receipt_number", "shift_id", "cashier_id", "customer_ref",
            "currency", "subtotal", "discount_total", "tax_total", "total", "kind",
            "original_sale_id", "status", "voided_by", "voided_reason", "server_total",
            "price_discrepancy", "sync_state", "created_at",
        )
    }
}
