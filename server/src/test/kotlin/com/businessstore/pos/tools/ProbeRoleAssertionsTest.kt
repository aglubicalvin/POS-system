package com.businessstore.pos.tools

import com.businessstore.pos.TestDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Executes the proposed role SQL, its SECURITY DEFINER preflight and its
 * assertion script against a disposable local PostgreSQL fixture. No Supabase
 * project, staging or production database is contacted, and the role is dropped
 * afterwards.
 *
 * The fixture reproduces the platform properties that matter here:
 *  - PostgreSQL grants USAGE on the `public` schema to PUBLIC, so every login
 *    inherits it, and grants EXECUTE on new functions to PUBLIC by default; the
 *    positive control therefore proves inherited USAGE is still accepted.
 *  - A SECURITY DEFINER function is executable through that default grant, which
 *    is exactly what the new preflight must stop until it has been reviewed.
 *
 * The only edits to the SQL under test are:
 *  - the database name in `GRANT CONNECT`, because the fixture database is not
 *    named `postgres`;
 *  - psql variable substitution (`:'admin_user'`,
 *    `:'reviewed_security_definer'`), which psql performs but JDBC does not.
 * Everything else runs verbatim, and the preflight-then-role helper reproduces
 * `psql --single-transaction -v ON_ERROR_STOP=1 -f preflight -f role`: a failure
 * in the first file aborts before the role is created.
 */
class ProbeRoleAssertionsTest {

    private val role = "pos_probe_readonly"
    private val roleSqlFile = File("tools/pos_probe_readonly.sql")
    private val preflightSqlFile = File("tools/pos_probe_readonly_preflight.sql")
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

    private fun runSql(sql: String): ScriptRun {
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

    private fun runScript(file: File, transform: (String) -> String = { it }): ScriptRun =
        runSql(transform(file.readText()))

    /** psql would expand :'admin_user'; the fixture runs as the connected login. */
    private fun roleSql(adminUser: String = dbUser): String =
        roleSqlFile.readText()
            .replace(
                "grant connect on database postgres to",
                "grant connect on database $dbName to",
            )
            .replace(":'admin_user'", "'${adminUser.replace("'", "''")}'")

    private fun preflightSql(reviewed: String): String =
        preflightSqlFile.readText()
            .replace(":'reviewed_security_definer'", "'${reviewed.replace("'", "''")}'")

    private fun assertionsSql(reviewed: String): String =
        "select set_config('pos.reviewed_security_definer', " +
            "'${reviewed.replace("'", "''")}', false);\n" +
            assertSqlFile.readText()

    private fun createRole() {
        val run = runSql(roleSql())
        assertEquals(null, run.failure, "the proposed role SQL must apply cleanly")
    }

    private fun runAssertions(reviewed: String = ""): ScriptRun =
        runSql(assertionsSql(reviewed))

    private fun runPreflight(reviewed: String): SQLException? =
        runSql(preflightSql(reviewed)).failure

    /**
     * `psql --single-transaction ... -f preflight -f role`: a refusal in the first
     * file means the second never runs and the transaction rolls back.
     */
    private fun preflightThenRole(reviewed: String): SQLException? {
        connect().use { conn ->
            conn.autoCommit = false
            val failure = try {
                conn.createStatement().use { it.execute(preflightSql(reviewed)) }
                conn.createStatement().use { it.execute(roleSql()) }
                conn.commit()
                null
            } catch (e: SQLException) {
                conn.rollback()
                e
            }
            return failure
        }
    }

    private fun failureText(run: ScriptRun): String = run.failure?.message.orEmpty()

    private fun roleExists(): Boolean =
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("select 1 from pg_roles where rolname = '$role'").use { it.next() }
            }
        }

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
        assertTrue(preflightSqlFile.isFile, "missing ${preflightSqlFile.absolutePath}")
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
        assertTrue(
            run.notices.contains("SECURITY DEFINER functions reviewed for pos_probe_readonly: 0"),
            run.notices,
        )
    }

    // ---- SECURITY DEFINER review gate, before creation --------------------

    @Test
    fun `the preflight refuses an unreviewed PUBLIC-executable SECURITY DEFINER function`() {
        execute(
            "CREATE FUNCTION public.reader() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'",
        )

        val failure = preflightThenRole("")
        assertNotNull(failure, "an unreviewed SECURITY DEFINER function must abort the run")
        assertTrue(failureText(ScriptRun(failure, "")).contains("unreviewed SECURITY DEFINER function"), failure!!.message)
        assertTrue(failure.message.orEmpty().contains("public.reader()"), failure.message)
        assertTrue(
            failure.message.orEmpty().contains("inherited EXECUTE from PUBLIC"),
            failure.message,
        )
        assertFalse(roleExists(), "a refused preflight must not create the role")
    }

    @Test
    fun `an allowlisted SECURITY DEFINER function passes the preflight and the assertions`() {
        execute(
            "CREATE FUNCTION public.reader() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'",
        )

        val failure = preflightThenRole("public.reader()")
        assertNull(failure, "an individually reviewed function must pass: ${failure?.message}")
        assertTrue(roleExists(), "the role must be created once the gate passes")

        val run = runAssertions("public.reader()")
        assertEquals(null, run.failure, run.notices)
        assertTrue(
            run.notices.contains("SECURITY DEFINER functions reviewed for pos_probe_readonly: 1"),
            run.notices,
        )
    }

    @Test
    fun `an ordinary function is not classified as SECURITY DEFINER`() {
        execute("CREATE FUNCTION public.helper() RETURNS int LANGUAGE sql AS 'SELECT 1'")

        val failure = preflightThenRole("")
        assertNull(failure, "a SECURITY INVOKER function needs no review: ${failure?.message}")
        assertTrue(roleExists())

        val run = runAssertions("")
        assertEquals(null, run.failure, run.notices)
        assertTrue(
            run.notices.contains("SECURITY DEFINER functions reviewed for pos_probe_readonly: 0"),
            run.notices,
        )
    }

    @Test
    fun `a direct EXECUTE grant is refused even when the function is allowlisted`() {
        createRole()
        execute(
            "CREATE FUNCTION public.reader() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'",
        )
        execute("GRANT EXECUTE ON FUNCTION public.reader() TO $role")

        val failure = runPreflight("public.reader()")
        assertNotNull(failure, "a direct function grant is not covered by the allowlist")
        assertTrue(
            failure!!.message.orEmpty().contains("direct grant to pos_probe_readonly"),
            failure.message,
        )
    }

    @Test
    fun `an explicit PUBLIC grant is reported as inherited EXECUTE`() {
        execute(
            "CREATE FUNCTION public.reader() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'",
        )
        execute("REVOKE ALL ON FUNCTION public.reader() FROM PUBLIC")
        execute("GRANT EXECUTE ON FUNCTION public.reader() TO PUBLIC")

        val failure = preflightThenRole("")
        assertNotNull(failure)
        assertTrue(
            failure!!.message.orEmpty().contains("inherited EXECUTE from PUBLIC"),
            failure.message,
        )
    }

    @Test
    fun `a SECURITY DEFINER function with no executable grant is not blocked`() {
        execute(
            "CREATE FUNCTION public.reader() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'",
        )
        execute("REVOKE ALL ON FUNCTION public.reader() FROM PUBLIC")

        val failure = preflightThenRole("")
        assertNull(failure, "nothing executable means nothing to review: ${failure?.message}")
        assertTrue(roleExists())
    }

    @Test
    fun `an allowlist entry that matches no executable function is refused`() {
        createRole()

        val failure = runPreflight("public.absent()")
        assertNotNull(failure, "a stale allowlist entry must not pass silently")
        assertTrue(
            failure!!.message.orEmpty().contains("match no executable SECURITY DEFINER function"),
            failure.message,
        )
    }

    @Test
    fun `an unreviewed inherited SECURITY DEFINER function fails the post-creation assertions`() {
        createRole()
        execute(
            "CREATE FUNCTION public.reader() RETURNS int LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'",
        )

        val run = runAssertions("")
        assertNotNull(run.failure, "the post-creation gate must fail closed too")
        assertTrue(
            failureText(run).contains("unreviewed SECURITY DEFINER function executable by pos_probe_readonly"),
            failureText(run),
        )
        assertTrue(failureText(run).contains("public.reader()"), failureText(run))
    }

    // ---- identity and sequencing -----------------------------------------

    @Test
    fun `the role SQL refuses a session that is not the validated administrator`() {
        val run = runSql(roleSql(adminUser = "postgres.someoneelse00000000"))

        assertNotNull(run.failure, "a different login must not create the role")
        assertTrue(failureText(run).contains("project identity refused"), failureText(run))
        assertFalse(roleExists())
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

        val run = runAssertions("public.reader()")
        assertNotNull(run.failure, "a direct EXECUTE grant must fail the assertions")
        assertTrue(failureText(run).contains("direct EXECUTE grant on a function"), failureText(run))
    }
}
