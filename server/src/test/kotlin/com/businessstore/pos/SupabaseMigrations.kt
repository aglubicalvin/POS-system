package com.businessstore.pos

import com.businessstore.pos.db.Database
import com.businessstore.pos.db.Schema
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Applies the Supabase migrations on top of the canonical Kotlin schema, once per
 * test JVM, after installing the slice of the Supabase environment they depend on
 * (the `auth` schema, the JWT helper and the PostgREST roles).
 *
 * Files are applied in filename order so a migration that assumes an object from
 * an earlier one fails here instead of in production. Several tests share this
 * harness, which is why it lives outside any one test class.
 */
object SupabaseMigrations {

    /** Every migration in the repo, in apply order. */
    val FILES = listOf(
        "0001_admin_foundation.sql",
        "0002_product_management.sql",
        "0003_dashboard_aggregation_and_authz.sql",
        "0004_rpc_search_path_hardening.sql",
        "0005_customers_payroll_hirepurchase.sql",
        "0006_phase43_workflows.sql",
        "0007_mobile_money_reconciliation.sql",
    )

    @Volatile
    private var applied = false

    @Synchronized
    fun ensure(db: Database) {
        if (applied) return
        db.read { conn -> installSupabaseShim(conn) }
        // Canonical POS schema first — the migrations layer on top of it.
        Schema.apply(db)
        for (file in FILES) {
            val sql = Files.readString(migrationsDir().resolve(file))
            db.read { conn -> conn.createStatement().use { it.execute(sql) } }
        }
        // Supabase's default privileges grant `authenticated` DML on every new table
        // in `public`, including ones a later migration creates. Re-running the grant
        // after the migrations reproduces that, so RLS (not a missing GRANT) decides
        // what a client can reach.
        db.read { conn ->
            conn.createStatement().use {
                it.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO authenticated")
                it.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO authenticated")
            }
        }
        applied = true
    }

    fun migrationsDir(): Path {
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
