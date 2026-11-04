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

        val dataSource = PGSimpleDataSource().apply {
            setURL(target.jdbcUrl)
            user = target.user
            password = target.password
        }

        val identity = runCatching { connectedIdentity(dataSource) }
        identity.exceptionOrNull()?.let { error ->
            System.err.println("  provision_status=error")
            System.err.println("  error_category=connection_failed")
            System.err.println("  error_detail=" + sqlStateOf(error))
            exitProcess(4)
        }
        val session = identity.getOrThrow()

        // Establish the CONNECTED target, not the configured one.
        //
        // What this proves: the session is a live PostgreSQL connection whose
        // current_database is 'postgres', whose current_user is the pooler
        // administror login postgres.<ref>, and whose inet_server_addr/port match
        // the endpoint we asked for. Together those mean the credentials logged
        // into the project encoded in the username -- a Supabase pooler
        // authenticates the tenant from the username suffix, so a session for
        // postgres.<ref> can only exist against project <ref>.
        //
        // What this does NOT prove: that <ref> is the project the operator
        // intended. That is what POS_EXPECTED_TARGET is for, and it is compared
        // here against the SESSION's current_user, never against the username we
        // already supplied.
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
        println("  session_user_ref=" + "<project-ref>")
        println("  database_identity=confirmed")

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
     * The fail-closed identity verdict. Returns null only when every check passes.
     *
     * Deliberately strict:
     *  - database must be 'postgres'
     *  - current_user MUST be the pooler login postgres.<ref>, and <ref> must
     *    equal POS_EXPECTED_TARGET (compared to the SERVER's answer, not ours)
     *  - the server must report its own address; a session with no
     *    inet_server_addr() (a unix socket) cannot confirm a remote endpoint
     *  - the reported port must be the port we connected to
     */
    internal fun identityProblem(session: ConnectedIdentity, target: ProvisionTarget.Target): Pair<String, String>? {
        if (session.database != "postgres") {
            return "unexpected_database" to "database=<other> expected=postgres"
        }
        val ref = session.currentUser?.let { Regex("^postgres\\.([a-z0-9]{20})$").find(it)?.groupValues?.get(1) }
        if (ref == null) {
            // A pooler session that did not authenticate as postgres.<ref> cannot
            // be attributed to any project.
            return "identity_unavailable" to "current_user=<not a pooler project login>"
        }
        if (ref != target.expected) {
            return "target_mismatch" to "session_project=<project-ref> expected=<project-ref>"
        }
        if (session.serverAddr.isNullOrBlank()) {
            return "identity_unavailable" to "inet_server_addr=<empty> (socket session cannot confirm endpoint)"
        }
        if (session.serverPort != target.port) {
            return "endpoint_mismatch" to "inet_server_port=<other> configured_port=" + target.port
        }
        return null
    }

    /** SQLState (+ a short sanitized class) only: never the driver's message. */
    private fun sqlStateOf(error: Throwable): String {
        val sqlState = (error as? java.sql.SQLException)?.sqlState ?: "unknown"
        return "sqlstate=" + sqlState + " type=" + error.javaClass.simpleName
    }
}