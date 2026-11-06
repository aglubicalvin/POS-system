package com.businessstore.pos.tools;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executor;

/**
 * The single transactional unit behind ApplyMigration: bootstrap the migration
 * history table, run one migration file, record that file, commit.
 *
 * Extracted from the command-line tool so tests drive the real implementation
 * against a disposable database instead of re-implementing its statement
 * sequence, which is how the missing bootstrap went unnoticed before.
 *
 * <p>Compatibility contract. The Supabase CLI owns
 * supabase_migrations.schema_migrations and creates it in four steps
 * (pkg/migration/history.go):
 *
 * <pre>
 *   CREATE SCHEMA IF NOT EXISTS supabase_migrations
 *   CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations
 *       (version text NOT NULL PRIMARY KEY)
 *   ALTER TABLE ... ADD COLUMN IF NOT EXISTS statements text[]
 *   ALTER TABLE ... ADD COLUMN IF NOT EXISTS name text
 * </pre>
 *
 * and inserts with {@code INSERT INTO ...(version, name, statements)
 * VALUES($1,$2,$3)}, passing a text[] for statements. Repeating those exact
 * steps means a table the CLI created is reused untouched, a table that
 * predates the optional columns is completed, and a fresh database ends up with
 * the CLI shape rather than a private one. {@code CREATE TABLE IF NOT EXISTS}
 * alone cannot add columns to an existing table, and a {@code statements jsonb}
 * table from the earlier ApplyMigration would reject a text[] insert with a
 * datatype mismatch, so the insert adapts to the column type that exists.
 *
 * <p>Recorded content. {@code statements} receives ONE element: the migration
 * file exactly as it was read from disk. The CLI stores one element per
 * statement and {@code supabase migration fetch} writes a row back as
 * {@code statements.join(";\n") + ";\n"}
 * (apps/cli/src/legacy/commands/migration/fetch/fetch.handler.ts), so a single
 * element reconstructs to the original file plus a trailing empty statement,
 * which PostgreSQL executes unchanged. Keeping the file whole is deliberate:
 * splitting on semicolons would corrupt dollar-quoted function bodies,
 * semicolons inside string literals and comments, and no SQL parser is
 * available here to split correctly. {@code version} is the leading digits of
 * the file name and {@code name} is the rest without {@code .sql} - the same
 * split the CLI's own file parser performs - so a fetched row lands back in
 * {@code <version>_<name>.sql}.
 *
 * <p>Connection lifecycle. apply() owns the TRANSACTION, never the connection:
 * it does not close it, and it restores the autocommit flag it found only once
 * a transaction has ended. On success the commit has already happened, so
 * enabling autocommit cannot commit anything new. On failure the transaction is
 * rolled back; if that rollback itself fails, autocommit is NEVER re-enabled
 * (JDBC commits an active transaction when autocommit is switched on, which is
 * exactly the outcome this runner exists to prevent) and the physical
 * connection is aborted so the server discards the transaction. The migration's
 * own exception stays primary and the cleanup failure is attached as
 * suppressed, so callers can distinguish "rolled back" from "outcome
 * uncertain".
 *
 * <p>Disposal is reported as an explicit {@link Disposal} - ABORTED,
 * CLOSED_FALLBACK or DISPOSAL_UNCERTAIN - and never inferred from the absence
 * of an error: a failed abort whose close() fallback worked is
 * CLOSED_FALLBACK, and two failed cleanups are DISPOSAL_UNCERTAIN. The abort and
 * close errors both remain attached as suppressed exceptions.
 *
 * <p>Duplicate versions. A version that is already recorded is rejected by a
 * pre-check AFTER the bootstrap DDL and BEFORE the migration SQL runs, so a
 * second invocation of a non-idempotent file cannot re-execute it ({@link
 * MigrationAlreadyApplied}). The pre-check alone cannot stop two runners that
 * both read the version as absent, so the history insert keeps
 * {@code on conflict (version) do nothing} and its affected-row count is
 * verified: zero rows means another runner recorded the version first and the
 * whole transaction must roll back ({@link MigrationVersionConflict}) rather
 * than commit schema changes that no history row vouches for.
 *
 * <p>Bootstrap locking. Each bootstrap statement is executed only when the
 * object it creates is actually missing. Running {@code alter table ... add
 * column if not exists} on a table that already has the column still takes
 * ACCESS EXCLUSIVE for the rest of the transaction, which would serialise every
 * concurrent runner on the history table and turn a duplicate-version race into
 * an invisible queue. The statements themselves are unchanged, so the CLI shape
 * is still what a fresh database ends up with.
 */
public final class MigrationHistory {

    /** The bootstrap the Supabase CLI performs, in the same order. */
    static final String[] BOOTSTRAP = {
        "create schema if not exists supabase_migrations",
        "create table if not exists supabase_migrations.schema_migrations "
            + "(version text not null primary key)",
        "alter table supabase_migrations.schema_migrations "
            + "add column if not exists statements text[]",
        "alter table supabase_migrations.schema_migrations "
            + "add column if not exists name text",
    };

    /**
     * Runs abort's blocking work on the calling thread, so the connection is
     * already terminated when apply() returns or throws.
     */
    private static final Executor INLINE = Runnable::run;

    private MigrationHistory() {
    }

    /**
     * Apply {@code sql} and record it under {@code version} in ONE transaction:
     * the bootstrap DDL, the migration and the history row commit together or
     * not at all. Any failure rolls every one of them back and is rethrown, with
     * the rollback or discard failure (if any) attached as suppressed.
     *
     * <p>Order matters and is part of the contract: read autocommit, disable it,
     * bootstrap, check the version, run the migration, insert history, verify one
     * row was inserted, commit. An already-recorded version never reaches the
     * migration SQL.
     *
     * @throws MigrationAlreadyApplied the version was recorded before this run;
     *     the migration SQL did not execute and the history row is unchanged.
     * @throws MigrationVersionConflict the history insert affected zero rows
     *     because a concurrent runner recorded the same version first; the
     *     migration SQL is rolled back, never committed.
     * @throws CommittedButUnclean the migration WAS committed, but the connection
     *     could not be returned in a clean state; {@link CommittedButUnclean#disposal}
     *     says whether it was aborted, closed, or left in an uncertain state.
     */
    public static void apply(Connection c, String version, String name, String sql)
            throws SQLException {
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            bootstrap(c);
            if (alreadyRecorded(c, version)) {
                throw new MigrationAlreadyApplied(version);
            }
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            insert(c, version, name, sql);
            c.commit();
        } catch (SQLException | RuntimeException failure) {
            rollbackAndDiscard(c, failure, autoCommit);
            throw failure;
        }
        // The commit is durable; there is no active transaction left to commit
        // by accident, so returning the connection in the mode it arrived in is
        // safe. A failure here is a connection problem, not a migration one.
        restoreAutoCommit(c, autoCommit);
    }

    /**
     * Roll back, then either hand the connection back in its original mode or
     * terminate it. Never re-enables autocommit while a failed transaction may
     * still be open.
     */
    private static void rollbackAndDiscard(Connection c, Throwable failure, boolean autoCommit) {
        try {
            c.rollback();
        } catch (SQLException | RuntimeException rollbackError) {
            failure.addSuppressed(new RollbackFailed(
                "rollback failed, so the transaction outcome is uncertain: "
                    + rollbackError.getMessage(),
                rollbackError));
            // setAutoCommit(true) here would COMMIT the transaction that is still
            // open, so the connection is terminated instead of being reset.
            discard(c, failure);
            return;
        }
        // The rollback completed: no transaction is active, so restoring the
        // caller's flag cannot commit anything.
        if (autoCommit) {
            try {
                c.setAutoCommit(true);
            } catch (SQLException | RuntimeException resetError) {
                failure.addSuppressed(new DiscardFailed(
                    "autocommit could not be restored after rollback: " + resetError.getMessage(),
                    resetError));
                discard(c, failure);
            }
        }
    }

    /** Restore the caller's autocommit flag after a durable commit. */
    private static void restoreAutoCommit(Connection c, boolean autoCommit)
            throws CommittedButUnclean {
        if (!autoCommit) {
            return;
        }
        try {
            c.setAutoCommit(true);
        } catch (SQLException | RuntimeException resetError) {
            CommittedButUnclean unclean = new CommittedButUnclean(
                "migration committed, but autocommit could not be restored: "
                    + resetError.getMessage(),
                resetError);
            unclean.disposal = discard(c, unclean);
            throw unclean;
        }
    }

    /**
     * Terminate the physical connection so PostgreSQL discards whatever
     * transaction is still open. abort() is preferred; close() is only a
     * fallback, because JDBC does not promise that closing a connection with an
     * open transaction rolls it back.
     *
     * <p>Returns which of the three things actually happened and attaches the
     * same facts as a {@link DisposalRecord} to {@code failure}, so a caller can
     * report the connection state truthfully. Every cleanup failure is attached
     * as {@link DiscardFailed}, never dropped: an abort that failed is recorded
     * even when the fallback close() succeeded.
     */
    private static Disposal discard(Connection c, Throwable failure) {
        boolean abortSucceeded = false;
        try {
            c.abort(INLINE);
            abortSucceeded = true;
        } catch (SQLException | RuntimeException abortError) {
            failure.addSuppressed(new DiscardFailed(
                "abort failed: " + abortError.getMessage(),
                abortError));
        }
        if (abortSucceeded) {
            failure.addSuppressed(new DisposalRecord(Disposal.ABORTED, true, false, false));
            return Disposal.ABORTED;
        }
        boolean closeSucceeded = false;
        try {
            c.close();
            closeSucceeded = true;
        } catch (SQLException | RuntimeException closeError) {
            failure.addSuppressed(new DiscardFailed(
                "close failed as well, so the connection may still be open: "
                    + closeError.getMessage(),
                closeError));
        }
        Disposal disposal = closeSucceeded
            ? Disposal.CLOSED_FALLBACK
            : Disposal.DISPOSAL_UNCERTAIN;
        failure.addSuppressed(new DisposalRecord(disposal, false, true, closeSucceeded));
        return disposal;
    }

    /**
     * How the runner disposed of a connection it could not reuse. There is no
     * "unknown" member: when the outcome is not provable, it is reported as
     * {@link #DISPOSAL_UNCERTAIN} rather than assumed clean.
     */
    public enum Disposal {
        /** abort() succeeded; the server terminated the session. */
        ABORTED,
        /** abort() failed, but the fallback close() succeeded. */
        CLOSED_FALLBACK,
        /** abort() and close() both failed; the transaction outcome is unknown. */
        DISPOSAL_UNCERTAIN
    }

    /**
     * The recorded disposal outcome: which cleanup was attempted, which one
     * worked, and whether anything is still open. Attached as a suppressed
     * exception so it travels with the primary failure.
     */
    public static final class DisposalRecord extends Exception {
        public final Disposal disposal;
        public final boolean abortSucceeded;
        public final boolean closeAttempted;
        public final boolean closeSucceeded;

        DisposalRecord(Disposal disposal, boolean abortSucceeded,
                       boolean closeAttempted, boolean closeSucceeded) {
            super("connection_disposal=" + disposal.name(), null, false, false);
            this.disposal = disposal;
            this.abortSucceeded = abortSucceeded;
            this.closeAttempted = closeAttempted;
            this.closeSucceeded = closeSucceeded;
        }
    }

    /**
     * The disposal outcome of a failed run, or null when the connection never
     * needed disposing (rollback succeeded and autocommit was restored).
     */
    public static Disposal disposalOf(Throwable failure) {
        if (failure instanceof CommittedButUnclean) {
            return ((CommittedButUnclean) failure).disposal;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof DisposalRecord) {
                return ((DisposalRecord) suppressed).disposal;
            }
        }
        return null;
    }

    /**
     * Execute the CLI bootstrap inside the caller's transaction, skipping every
     * statement whose object already exists so an up-to-date table is not locked
     * for the duration of the migration.
     */
    private static void bootstrap(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            if (!queryExists(c, "select 1 from information_schema.schemata "
                    + "where schema_name = 'supabase_migrations'")) {
                st.execute(BOOTSTRAP[0]);
            }
            if (!queryExists(c, "select 1 from information_schema.tables "
                    + "where table_schema = 'supabase_migrations' "
                    + "and table_name = 'schema_migrations'")) {
                st.execute(BOOTSTRAP[1]);
            }
            if (statementsType(c) == null) {
                st.execute(BOOTSTRAP[2]);
            }
            if (!columnExists(c, "name")) {
                st.execute(BOOTSTRAP[3]);
            }
        }
    }

    private static boolean queryExists(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    private static boolean columnExists(Connection c, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select 1 from information_schema.columns "
                + "where table_schema = 'supabase_migrations' "
                + "and table_name = 'schema_migrations' "
                + "and column_name = ?")) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Whether {@code version} is already in supabase_migrations.schema_migrations.
     * Read inside the caller's transaction, after the bootstrap created the table,
     * so a duplicate is rejected before any migration SQL runs.
     */
    static boolean alreadyRecorded(Connection c, String version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select 1 from supabase_migrations.schema_migrations where version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Record the migration under the column type already present. {@code sql} is
     * the migration file itself, kept as one element; see the class comment.
     *
     * <p>EXACTLY one row must be inserted. {@code on conflict (version) do
     * nothing} is kept so a concurrent runner loses by row count instead of
     * failing on a constraint, but a zero count is never accepted as success:
     * the caller has just executed migration SQL that this row was supposed to
     * vouch for.
     */
    private static void insert(Connection c, String version, String name, String sql)
            throws SQLException {
        boolean jsonb = "jsonb".equalsIgnoreCase(statementsType(c));
        String statement = jsonb
            ? "insert into supabase_migrations.schema_migrations (version, name, statements) "
                + "values (?, ?, jsonb_build_array(?::text)) on conflict (version) do nothing"
            : "insert into supabase_migrations.schema_migrations (version, name, statements) "
                + "values (?, ?, ?) on conflict (version) do nothing";
        try (PreparedStatement ps = c.prepareStatement(statement)) {
            ps.setString(1, version);
            ps.setString(2, name);
            if (jsonb) {
                ps.setString(3, sql);
            } else {
                ps.setArray(3, c.createArrayOf("text", new String[] {sql}));
            }
            int inserted = ps.executeUpdate();
            if (inserted != 1) {
                throw new MigrationVersionConflict(version, inserted);
            }
        }
    }

    /**
     * Declared type of schema_migrations.statements: "ARRAY" for the CLI's
     * text[], "jsonb" for the earlier table, or null when the column is absent.
     */
    static String statementsType(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select data_type from information_schema.columns "
                + "where table_schema = 'supabase_migrations' "
                + "and table_name = 'schema_migrations' "
                + "and column_name = 'statements'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Whether the rollback of a failed migration threw. */
    public static boolean rollbackFailed(Throwable failure) {
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof RollbackFailed) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether abort() or the fallback close() failed. True means the record
     * still carries a discard error; the outcome itself is in
     * {@link #disposalOf(Throwable)}.
     */
    public static boolean discardFailed(Throwable failure) {
        for (Throwable suppressed : failure.getSuppressed()) {
            if (suppressed instanceof DiscardFailed) {
                return true;
            }
        }
        return false;
    }

    /**
     * The requested version is already recorded. Raised after the bootstrap and
     * before the migration SQL, so nothing is re-executed and the existing
     * history row and its recorded SQL are untouched. The transaction is rolled
     * back by apply().
     */
    public static final class MigrationAlreadyApplied extends SQLException {
        public final String version;

        MigrationAlreadyApplied(String version) {
            super("migration version " + version + " is already recorded in "
                + "supabase_migrations.schema_migrations; refusing to execute it again",
                "23505");
            this.version = version;
        }
    }

    /**
     * A concurrent runner recorded the same version between this run's pre-check
     * and its history insert, so zero rows were inserted. The migration SQL in
     * this transaction must not commit; apply() rolls it back.
     */
    public static final class MigrationVersionConflict extends SQLException {
        public final String version;
        public final int rowsInserted;

        MigrationVersionConflict(String version, int rowsInserted) {
            super("migration version " + version + " was recorded concurrently by another "
                + "runner (rows inserted: " + rowsInserted + "); the migration was rolled back",
                "23505");
            this.version = version;
            this.rowsInserted = rowsInserted;
        }
    }

    /**
     * The rollback of a failed migration threw, so the transaction may still be
     * live. The connection has been aborted; the migration's own exception
     * remains primary and carries this as suppressed.
     */
    public static final class RollbackFailed extends SQLException {
        RollbackFailed(String reason, Throwable cause) {
            super(reason, "08006", cause);
        }
    }

    /**
     * The failed connection could not be aborted or closed. Its transaction
     * outcome is unknown; the migration file must be inspected by hand.
     */
    public static final class DiscardFailed extends SQLException {
        DiscardFailed(String reason, Throwable cause) {
            super(reason, "08003", cause);
        }
    }

    /**
     * The migration committed, but the connection could not be returned to its
     * original autocommit mode and was discarded. The migration IS applied;
     * {@link #disposal} states exactly how the connection ended.
     */
    public static final class CommittedButUnclean extends SQLException {
        /** Never null by the time this escapes apply(). */
        public Disposal disposal;

        CommittedButUnclean(String reason, Throwable cause) {
            super(reason, "08003", cause);
        }
    }
}
