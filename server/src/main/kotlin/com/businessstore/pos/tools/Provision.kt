package com.businessstore.pos.tools

import com.businessstore.pos.db.Schema
import com.businessstore.pos.db.SchemaVerifier
import org.postgresql.ds.PGSimpleDataSource
import kotlin.system.exitProcess

/**
 * Explicit baseline provisioning: the only way this server creates schema.
 *
 * Reached exclusively through `./gradlew provisionDatabase`, never from
 * application startup. The sequence is deliberate and each step is a gate:
 *
 *   1. validate all configuration locally (no connection before this passes)
 *   2. connect to the confirmed target only
 *   3. prove the connected database really is the expected one
 *   4. apply the baseline and record version 0000 in ONE transaction
 *   5. verify the baseline afterwards and only then report success
 *
 * Exit codes: 0 provisioned or already provisioned; 2 configuration rejected;
 * 3 connected database is not the expected target; 4 provisioning failed;
 * 5 baseline absent after a reported success (should be impossible).
 *
 * Nothing here prints a password, a full connection string or a host: target
 * descriptions are redacted, and driver errors are reduced to their SQLState.
 */
object Provision {

    @JvmStatic
    fun main(args: Array<String>) {
        val outcome = ProvisionTarget.resolve()
        if (outcome is ProvisionTarget.Outcome.Rejected) {
            System.err.println("PROVISION_REJECTED code=${outcome.code}")
            System.err.println("  " + outcome.message)
            exitProcess(2)
        }
        val target = (outcome as ProvisionTarget.Outcome.Ok).target
        println("== explicit baseline provisioning ==")
        println("  target: " + target.describe())

        // Build the connector from VALIDATED FIELDS, never from the raw URL.
        //
        // pgJDBC's BaseDataSource.setUrl() copies every URL parameter into the
        // property map BEFORE our setter runs, and a property already present is
        // not overwritten -- so `...?sslmode=verify-full&sslfactory=org.postgresql
        // .ssl.NonValidatingFactory` would install a trust-all TrustManager and
        // silently defeat verify-full. ProvisionTarget rejects such parameters,
        // and this constructor removes the possibility entirely: no URL is handed
        // to the driver, and every security-relevant setting is assigned here,
        // after the host/port/database fields, in an order we control.
        val dataSource = buildDataSource(target)

        val identity = runCatching { connectedIdentity(dataSource) }
        identity.exceptionOrNull()?.let { error ->
            System.err.println("  provision_status=error")
            System.err.println("  error_category=connection_failed")
            System.err.println("  error_detail=" + sqlStateOf(error))
            exitProcess(4)
        }
        val session = identity.getOrThrow()

        // Establish what can actually be established about the connected target.
        //
        // TLS (sslmode=verify-full with a pinned root CA, enforced in
        // ProvisionTarget before this point) is what proves the SERVER'S IDENTITY:
        // the peer presented a chain from the Supabase root and a certificate
        // valid for the hostname we dialled. That is the strong guarantee, and it
        // happens in the driver, not in SQL.
        //
        // The session query below adds weaker, corroborating facts:
        //   current_database() -> we are in the 'postgres' database
        //   current_user       -> the role the SESSION authenticated as
        //
        // What it does NOT prove, and must not be described as proving:
        //   - A Supabase pooler does not necessarily expose the original login in
        //     current_user; behind a shared pooler the session may be attributed
        //     to a different internal role. So the project suffix seen here is
        //     corroborating, not authoritative.
        //   - inet_server_addr()/inet_server_port() report the address and port
        //     the SERVER accepted the connection on (PostgreSQL docs, System
        //     Information Functions). Behind a pooler that is the pooler's
        //     backend-side socket, NOT the client-facing endpoint. Comparing it to
        //     the pooler port we dialled compares two different things, so it is
        //     deliberately NOT used as an identity signal.
        //
        // The project identity is therefore ultimately a HUMAN-CONTROLLED
        // assertion: POS_EXPECTED_TARGET states what the operator believes, and
        // it is checked against the session's current_user where the pooler
        // exposes it. If it is not exposed, we say so and proceed on the strength
        // of verified TLS plus the configured target -- never claiming more.
        //
        // If any signal is unavailable the verdict is fail-closed: no identity, no
        // provisioning.
        val problem = identityProblem(session, target)
        if (problem != null) {
            System.err.println("  provision_status=error")
            System.err.println("  error_category=target_identity_not_confirmed")
            System.err.println("  error_detail=" + problem.second)
            exitProcess(3)
        }
        println("  tls=verify-full (chain pinned to " + ProvisionTarget.ROOT_CERT_ENV + ")")
        println("  session_database=confirmed")
        if (identityWeakness(session)) {
            // Be explicit rather than implying a guarantee the provider does not
            // give: identity here rests on verified TLS + POS_EXPECTED_TARGET.
            println("  database_identity=confirmed_by_tls_and_expected_target")
            println("  identity_caveat=current_user did not expose a project suffix")
        } else {
            println("  database_identity=confirmed (tls + session project)")
        }

        val created = try {
            Schema.apply(dataSource)
        } catch (error: Throwable) {
            System.err.println("  provision_status=error")
            System.err.println("  error_category=provisioning_failed")
            System.err.println("  error_detail=" + sqlStateOf(error))
            System.err.println("  transaction=rolled_back")
            exitProcess(4)
        }

        // A reported success must be observable. Re-read the baseline rather than
        // trusting the commit: if it is absent, provisioning did not take effect.
        val verification = runCatching { SchemaVerifier.inspect(dataSource) }
        val result = verification.getOrNull()
        if (result == null || !result.provisioned) {
            System.err.println("  provision_status=error")
            System.err.println("  error_category=baseline_not_verified")
            System.err.println("  error_detail=" + (result?.describe() ?: "verification_failed"))
            exitProcess(5)
        }

        println("  baseline_version=" + SchemaVerifier.REQUIRED_VERSION)
        println("  " + result.describe())
        println("  provision_status=" + if (created) "created" else "already_provisioned")
        println("  note=migrations 0001-0009 are NOT applied by this run")
    }

    /**
     * The only place a remote connector is configured.
     *
     * Every security-relevant property is set explicitly and AFTER the connection
     * fields, so nothing can pre-empt it:
     *   sslmode=verify-full      chain + hostname verification
     *   sslrootcert=<file>       pin the provider CA instead of the JVM store
     *   sslfactory               left at the driver default (LibPQFactory), which
     *                            honours sslmode; never a non-validating factory
     *   sslhostnameverifier      left at the driver default for verify-full
     *
     * Returns the configured PGSimpleDataSource for inspection by tests.
     */
    internal fun buildDataSource(target: ProvisionTarget.Target): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setServerNames(arrayOf(target.host))
            setPortNumbers(intArrayOf(target.port))
            setDatabaseName(target.database)
            user = target.user
            password = target.password
            // Explicit TLS configuration; no value here can come from a URL.
            setSslMode("verify-full")
            setSslRootCert(target.rootCertificatePath)
        }

    /** Facts about the CONNECTED session, read with a single read-only query. */
    internal data class ConnectedIdentity(
        val database: String?,
        val currentUser: String?,
        val serverAddr: String?,
        val serverPort: Int?,
    )

    /**
     * Ask PostgreSQL who we actually are and where we actually landed.
     *
     * Every column here is server-reported session state, not configuration we
     * supplied: current_database(), current_user, and the backend's own
     * inet_server_addr()/inet_server_port().
     */
    internal fun connectedIdentity(dataSource: PGSimpleDataSource): ConnectedIdentity =
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "select current_database(), current_user, " +
                        "coalesce(host(inet_server_addr()), ''), inet_server_port()"
                ).use { rs ->
                    if (!rs.next()) error("no identity row returned")
                    ConnectedIdentity(
                        database = rs.getString(1),
                        currentUser = rs.getString(2),
                        serverAddr = rs.getString(3),
                        serverPort = rs.getInt(4),
                    )
                }
            }
        }

    /**
     * The fail-closed identity verdict. Returns null only when every REQUIRED
     * check passes.
     *
     * Required:
     *  - the connected database must be 'postgres'
     *  - if the pooler exposes a project-suffixed current_user, it MUST equal
     *    POS_EXPECTED_TARGET (a disagreement is fatal)
     *
     * Deliberately NOT used: inet_server_addr()/inet_server_port(). Behind a
     * shared pooler those describe the backend-side socket, not the endpoint the
     * client dialled, so comparing them to the pooler host/port would be a false
     * assurance.
     *
     * When the pooler does not expose a suffixed login, identity rests on
     * verified TLS plus the operator's configured target; [identityWeakness]
     * reports that so the run can say so out loud instead of overclaiming.
     */
    internal fun identityProblem(session: ConnectedIdentity, target: ProvisionTarget.Target): Pair<String, String>? {
        if (session.database != "postgres") {
            return "unexpected_database" to "database=<other> expected=postgres"
        }
        val ref = session.currentUser?.let { Regex("^postgres\\.([a-z0-9]{20})$").find(it)?.groupValues?.get(1) }
        if (ref != null && ref != target.expected) {
            return "target_mismatch" to "session_project=<project-ref> expected=<project-ref>"
        }
        return null
    }

    /**
     * Non-fatal caveat to print: true when the session did not expose a project
     * suffix, so project identity is corroborated only by TLS + configuration.
     */
    internal fun identityWeakness(session: ConnectedIdentity): Boolean =
        session.currentUser?.let { Regex("^postgres\\.([a-z0-9]{20})$").find(it) } == null

    /** SQLState (+ a short sanitized class) only: never the driver's message. */
    private fun sqlStateOf(error: Throwable): String {
        val sqlState = (error as? java.sql.SQLException)?.sqlState ?: "unknown"
        return "sqlstate=" + sqlState + " type=" + error.javaClass.simpleName
    }
}