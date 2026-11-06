package com.businessstore.pos.tools;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The single transactional unit behind ApplyMigration: bootstrap the migration
 * history table, run one migration file, record it, commit.
 *
 * Extracted from the command-line tool so tests drive the real implementation
 * against a disposable database instead of re-implementing its statement
 * sequence, which is how the missing bootstrap went unnoticed before.
 *
 * Compatibility contract. The Supabase CLI owns
 * supabase_migrations.schema_migrations and creates it in three steps
 * (pkg/migration/history.go):
 *
 *   CREATE SCHEMA IF NOT EXISTS supabase_migrations
 *   CREATE TABLE IF NOT EXISTS supabase_migrations.schema_migrations
 *       (version text NOT NULL PRIMARY KEY)
 *   ALTER TABLE ... ADD COLUMN IF NOT EXISTS statements text[]
 *   ALTER TABLE ... ADD COLUMN IF NOT EXISTS name text
 *
 * and inserts with `INSERT INTO ...(version, name, statements) VALUES($1,$2,$3)`,
 * passing a text[] for statements. Repeating those exact steps means a table the
 * CLI created is reused untouched, a table that predates the optional columns is
 * completed, and a fresh database ends up with the CLI shape rather than a
 * private one. `CREATE TABLE IF NOT EXISTS` alone cannot add columns to an
 * existing table, and a `statements jsonb` table from the earlier ApplyMigration
 * would reject a text[] insert with a datatype mismatch, so the insert adapts to
 * the column type that actually exists.
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

    /** Written to `statements` so a reader can tell how the row got there. */
    public static final String PROVENANCE = "applied by ApplyMigration";

    private MigrationHistory() {
    }

    /**
     * Apply {@code sql} and record {@code version} in ONE transaction: the
     * bootstrap DDL, the migration and the history row commit together or not at
     * all. Any failure rolls back every one of them and is rethrown.
     */
    public static void apply(Connection c, String version, String name, String sql)
            throws SQLException {
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (Statement st = c.createStatement()) {
                for (String ddl : BOOTSTRAP) {
                    st.execute(ddl);
                }
                st.execute(sql);
            }
            insert(c, version, name);
            c.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                c.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        } finally {
            try {
                c.setAutoCommit(autoCommit);
            } catch (SQLException ignored) {
                // The connection is already failing; the caller reports it.
            }
        }
    }

    /** Record the version and name, matching the column type already present. */
    private static void insert(Connection c, String version, String name) throws SQLException {
        boolean jsonb = "jsonb".equalsIgnoreCase(statementsType(c));
        String sql = jsonb
            ? "insert into supabase_migrations.schema_migrations (version, name, statements) "
                + "values (?, ?, to_jsonb(?::text)) on conflict (version) do nothing"
            : "insert into supabase_migrations.schema_migrations (version, name, statements) "
                + "values (?, ?, ?) on conflict (version) do nothing";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, version);
            ps.setString(2, name);
            if (jsonb) {
                ps.setString(3, PROVENANCE);
            } else {
                ps.setArray(3, c.createArrayOf("text", new String[] {PROVENANCE}));
            }
            ps.executeUpdate();
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
}
