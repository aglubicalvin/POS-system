package com.businessstore.pos.tools

import com.businessstore.pos.TestDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Executes the proposed role SQL and its assertion script against a disposable
 * local PostgreSQL fixture. No Supabase project, staging or production database is
 * contacted, and the role is dropped afterwards.
 *
 * The fixture reproduces the one platform property that made the old assertion
 * wrong: PostgreSQL/Supabase grant USAGE on the `public` schema to PUBLIC, so every
 * login inherits it. The fixture therefore also proves the positive control --
 * `has_schema_privilege(..., 'public', 'USAGE')` is true and the assertions still
 * pass -- rather than deleting the check and calling it fixed.
 *
 * The only edit to the SQL under test is the database name in `GRANT CONNECT`,
 * because the fixture database is not called `postgres`. Everything else in both
 * files runs verbatim.
 */
class ProbeRoleAssertionsTest {

    private val role = "pos_probe_readonly"
    private val roleSqlFile = File("tools/pos_probe_readonly.sql")
    private val assertSqlFile = File("tools/pos_probe_readonly_assert.sql")

    private lateinit var dbName: String
    private lateinit var url: String
    private lateinit var dbUser: String

    @BeforeEach
    fun setUp() {
        dbName = "probe_role_fixture_${UUID.randomUUID().toString().replace("-", "")}"
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $dbName") }
            url = conn.metaData.url.substringBeforeLast('/') + "/$dbName"
            dbUser = conn.metaData.userName
        }
        execute(fixtureDdl())
        dropRole()
    }

    @AfterEach
    fun tearDown() {
        // Privileges granted to the role are dependencies; release them first.
        runCatching { execute("DROP OWNED BY $role") }
        dropRole()
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS $dbName") }
        }
    }

    private fun connect(): Connection = java.sql.DriverManager.getConnection(url, dbUser, "")

    private fun execute(sql: String) {
        connect().use { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    /** Where a script raises, keep the exception and the notice text for assertions. */
    private data class ScriptRun(val failure: SQLException?, val notices: String)

    private fun runScript(file: File, transform: (String) -> String = { it }): ScriptRun {
        val sql = transform(file.readText())
        connect().use { conn ->
            conn.createStatement().use { st ->
                val failure = runCatching { st.execute(sql) }.exceptionOrNull() as? SQLException
                val notices = buildString {
                    var warning = st.warnings
                    while (warning != null) {
                        append(warning.message).append('\n')
                        warning = warning.nextWarning
                    }
                }
                return ScriptRun(failure, notices)
            }
        }
    }

    private fun createRole() {
        val run = runScript(roleSqlFile) { sql ->
            sql.replace(
                "grant connect on database postgres to",
                "grant connect on database $dbName to",
            )
        }
        assertEquals(null, run.failure, "the proposed role SQL must apply cleanly")
    }

    private fun runAssertions(): ScriptRun = runScript(assertSqlFile)

    private fun failureText(run: ScriptRun): String = run.failure?.message.orEmpty()

    private fun dropRole() {
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("DROP ROLE IF EXISTS $role")
            }
        }
    }

    private fun privilege(sql: String): Boolean =
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }

    /** Supabase's baseline: PUBLIC can use `public` but cannot create in it. */
    private fun fixtureDdl(): String {
        assertTrue(roleSqlFile.isFile, "missing ${roleSqlFile.absolutePath}")
        assertTrue(assertSqlFile.isFile, "missing ${assertSqlFile.absolutePath}")
        return """
            CREATE SCHEMA IF NOT EXISTS auth;
            CREATE TABLE auth.users (id int PRIMARY KEY);
            CREATE TABLE auth.identities (id int PRIMARY KEY);
            CREATE TABLE public.profiles (id int PRIMARY KEY);
            CREATE TABLE public.customers (id int PRIMARY KEY);
            CREATE TABLE public.sales (id int PRIMARY KEY);
            CREATE TABLE public.payments (id int PRIMARY KEY);
            CREATE SCHEMA IF NOT EXISTS supabase_migrations;
            CREATE TABLE supabase_migrations.schema_migrations (
                version bigint PRIMARY KEY,
                name text,
                statements text,
                created_by text
            );
            INSERT INTO supabase_migrations.schema_migrations (version, name, statements, created_by)
            VALUES (1, 'baseline', 'select 1', 'fixture');
            GRANT USAGE ON SCHEMA public TO PUBLIC;
            REVOKE CREATE ON SCHEMA public FROM PUBLIC;
        """.trimIndent()
    }

    // ---- positive control -------------------------------------------------

    @Test
    fun `the proposed role passes the assertions with inherited public USAGE`() {
        createRole()

        assertTrue(
            privilege("select has_schema_privilege('$role', 'public', 'USAGE')"),
            "the fixture must reproduce the inherited USAGE that the old assertion rejected",
        )
        assertFalse(privilege("select has_schema_privilege('$role', 'public', 'CREATE')"))
        assertFalse(privilege("select has_table_privilege('$role', 'public.profiles', 'SELECT')"))
        assertFalse(privilege("select has_table_privilege('$role', 'auth.users', 'SELECT')"))
        assertTrue(
            privilege("select has_column_privilege('$role', 'supabase_migrations.schema_migrations', 'version', 'SELECT')"),
        )
        assertFalse(
            privilege("select has_column_privilege('$role', 'supabase_migrations.schema_migrations', 'statements', 'SELECT')"),
        )

        val run = runAssertions()
        assertEquals(null, run.failure, "assertions must pass on the proposed role:\n${run.notices}")
        assertTrue(run.notices.contains("assertions passed"), run.notices)
    }

    // ---- regressions the assertions must still catch ----------------------

    @Test
    fun `a direct table grant is rejected`() {
        createRole()
        execute("GRANT SELECT ON public.profiles TO $role")

        val run = runAssertions()
        assertNotNull(run.failure, "a business-table grant must fail the assertions")
        assertTrue(failureText(run).contains("business or auth table SELECT granted"), failureText(run))
        assertTrue(failureText(run).contains("unexpected effective table privilege"), failureText(run))
    }

    @Test
    fun `a grant to PUBLIC on a business table is rejected as an inherited privilege`() {
        createRole()
        execute("GRANT SELECT ON public.sales TO PUBLIC")

        val run = runAssertions()
        assertNotNull(run.failure, "an inherited business-table grant must fail the assertions")
        assertTrue(failureText(run).contains("unexpected effective table privilege"), failureText(run))
    }

    @Test
    fun `CREATE on the public schema is rejected`() {
        createRole()
        execute("GRANT CREATE ON SCHEMA public TO $role")

        val run = runAssertions()
        assertNotNull(run.failure, "CREATE must fail the assertions")
        assertTrue(failureText(run).contains("CREATE on public schema"), failureText(run))
    }

    @Test
    fun `a direct schema ACL entry is rejected even though inherited USAGE is allowed`() {
        createRole()
        execute("GRANT USAGE ON SCHEMA public TO $role")

        val run = runAssertions()
        assertNotNull(run.failure, "a direct public ACL entry must fail the assertions")
        assertTrue(
            failureText(run).contains("direct ACL entry for the role on public schema"),
            failureText(run),
        )
    }

    @Test
    fun `a direct function grant is rejected`() {
        createRole()
        execute(
            "CREATE FUNCTION public.reader() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'",
        )
        execute("GRANT EXECUTE ON FUNCTION public.reader() TO $role")

        val run = runAssertions()
        assertNotNull(run.failure, "a direct EXECUTE grant must fail the assertions")
        assertTrue(failureText(run).contains("direct EXECUTE grant on a function"), failureText(run))
    }

    @Test
    fun `inherited SECURITY DEFINER functions are reported, not asserted away`() {
        createRole()
        execute(
            "CREATE FUNCTION public.reader() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'",
        )

        val run = runAssertions()
        assertEquals(null, run.failure, "inherited EXECUTE is the platform baseline here:\n${run.notices}")
        assertTrue(run.notices.contains("SECURITY DEFINER functions"), run.notices)
        assertTrue(run.notices.contains("public.reader"), run.notices)
    }
}
