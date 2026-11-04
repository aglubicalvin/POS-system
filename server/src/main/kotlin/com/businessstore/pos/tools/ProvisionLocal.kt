package com.businessstore.pos.tools

import com.businessstore.pos.db.Schema
import com.businessstore.pos.db.SchemaVerifier
import org.postgresql.ds.PGSimpleDataSource
import kotlin.system.exitProcess

/**
 * Explicit LOCAL baseline provisioning (`./gradlew provisionLocalDatabase`).
 *
 * The local counterpart to [Provision]. It exists because the remote provisioner
 * requires a Supabase pooler login and a project reference, which a developer
 * laptop does not have, while normal startup refuses any database whose baseline
 * row 0000 is missing. Applying schema.sql by hand with psql produced exactly such
 * a database: the tables existed but nothing recorded version 0000, so the server
 * would refuse to start.
 *
 * Loopback only, explicitly confirmed, same transactional Schema.apply, and -- like
 * the remote path -- it never applies migrations 0001-0009.
 *
 * Exit codes: 0 provisioned or already provisioned; 2 configuration rejected;
 * 3 connected database is not the configured local database; 4 provisioning
 * failed; 5 baseline absent after a successful run.
 */
object ProvisionLocal {

    @JvmStatic
    fun main(args: Array<String>) {
        val outcome = LocalProvisionTarget.resolve()
        if (outcome is LocalProvisionTarget.Outcome.Rejected) {
            System.err.println("LOCAL_PROVISION_REJECTED code=" + outcome.code)
            System.err.println("  " + outcome.message)
            exitProcess(2)
        }
        val target = (outcome as LocalProvisionTarget.Outcome.Ok).target
        println("== explicit local baseline provisioning ==")
        println("  target: " + target.describe())

        val dataSource = PGSimpleDataSource().apply {
            setURL(target.jdbcUrl)
            user = target.user
            password = target.password
        }

        val name = runCatching {
            dataSource.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("select current_database()").use { rs -> rs.next(); rs.getString(1) }
                }
            }
        }.getOrElse { error ->
            System.err.println("  provision_status=error")
            System.err.println("  error_category=connection_failed")
            System.err.println("  error_detail=" + sqlStateOf(error))
            exitProcess(4)
        }
        if (name != target.database) {
            System.err.println("  provision_status=error")
            System.err.println("  error_category=unexpected_database")
            System.err.println("  error_detail=database=<other> configured=" + target.database)
            exitProcess(3)
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

        val result = runCatching { SchemaVerifier.inspect(dataSource) }.getOrNull()
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

    private fun sqlStateOf(error: Throwable): String {
        val sqlState = (error as? java.sql.SQLException)?.sqlState ?: "unknown"
        return "sqlstate=" + sqlState + " type=" + error.javaClass.simpleName
    }
}