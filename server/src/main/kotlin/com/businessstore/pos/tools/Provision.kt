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

        val identity = runCatching { databaseIdentity(dataSource) }
        identity.exceptionOrNull()?.let { error ->
            System.err.println("  provision_status=error")
            System.err.println("  error_category=connection_failed")
            System.err.println("  error_detail=" + sqlStateOf(error))
            exitProcess(4)
        }
        val name = identity.getOrThrow()
        if (name != "postgres") {
            System.err.println("  provision_status=error")
            System.err.println("  error_category=unexpected_database")
            System.err.println("  error_detail=database=<other> expected=postgres")
            exitProcess(3)
        }
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

    /** current_database(), read-only. */
    private fun databaseIdentity(dataSource: PGSimpleDataSource): String =
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("select current_database()").use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    /** SQLState (+ a short sanitized class) only: never the driver's message. */
    private fun sqlStateOf(error: Throwable): String {
        val sqlState = (error as? java.sql.SQLException)?.sqlState ?: "unknown"
        return "sqlstate=" + sqlState + " type=" + error.javaClass.simpleName
    }
}