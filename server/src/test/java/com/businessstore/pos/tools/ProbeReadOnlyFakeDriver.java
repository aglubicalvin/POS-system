package com.businessstore.pos.tools;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Test-only JDBC driver used to prove what ProbeState does when PostgreSQL does
 * not confirm a read-only transaction.
 *
 * The real ProbeState is launched in a child JVM with
 * {@code -Djdbc.drivers=com.businessstore.pos.tools.ProbeReadOnlyFakeDriver}, so
 * the driver is registered by DriverManager before pgJDBC. The URL is the one the
 * probe's parser already accepts for a loopback fixture, which means no test-only
 * environment variable, URL parameter or production backdoor is introduced: the
 * probe under test is the shipped build and only its database is fake.
 *
 * Modes (system property {@code probe.fake.mode}):
 *   off     - `show transaction_read_only` returns "off"    -> exit 6
 *   missing - the query returns no row                      -> exit 6
 *   throw   - the query fails with an SQLException          -> exit 6
 *   on      - returns "on" (control: the probe proceeds)
 *
 * Every other query throws, so a catalog query reaching this connection is a
 * visible test failure rather than silent success.
 */
public final class ProbeReadOnlyFakeDriver implements Driver {

    public static final String URL_PREFIX = "jdbc:postgresql://127.0.0.1:1/probe-readonly-fixture";

    private static String mode() {
        String value = System.getProperty("probe.fake.mode", "off");
        return value == null ? "off" : value;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        final String mode = mode();
        InvocationHandler connection = (proxy, method, args) -> {
            switch (method.getName()) {
                case "close":
                case "setAutoCommit":
                case "setReadOnly":
                case "rollback":
                case "commit":
                case "isClosed":
                    if ("isClosed".equals(method.getName())) return Boolean.FALSE;
                    return null;
                case "isReadOnly":
                    return Boolean.TRUE;
                case "createStatement":
                    return statement(mode);
                case "toString":
                    return "ProbeReadOnlyFakeConnection(" + mode + ")";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new SQLException("fake driver: unsupported Connection." + method.getName());
            }
        };
        return (Connection) Proxy.newProxyInstance(
            ProbeReadOnlyFakeDriver.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            connection
        );
    }

    private static Statement statement(String mode) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "executeQuery":
                    String sql = String.valueOf(args[0]).trim().toLowerCase();
                    if (!sql.equals("show transaction_read_only")) {
                        // A catalog query must never run after a failed check.
                        throw new AssertionError("unexpected query after read-only check: " + sql);
                    }
                    if ("throw".equals(mode)) {
                        throw new SQLException("fixture refuses to report transaction_read_only");
                    }
                    if ("missing".equals(mode)) return resultSet(null);
                    if ("on".equals(mode)) return resultSet("on");
                    return resultSet("off");
                case "close":
                    return null;
                case "getConnection":
                    throw new SQLException("fake driver: no connection handle");
                case "toString":
                    return "ProbeReadOnlyFakeStatement(" + mode + ")";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new SQLException("fake driver: unsupported Statement." + method.getName());
            }
        };
        return (Statement) Proxy.newProxyInstance(
            ProbeReadOnlyFakeDriver.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            handler
        );
    }

    private static ResultSet resultSet(String value) {
        final boolean[] consumed = {false};
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "next":
                    if (value == null) return Boolean.FALSE;
                    if (consumed[0]) return Boolean.FALSE;
                    consumed[0] = true;
                    return Boolean.TRUE;
                case "getString":
                    return value;
                case "close":
                    return null;
                case "wasNull":
                    return Boolean.FALSE;
                case "toString":
                    return "ProbeReadOnlyFakeResultSet(" + value + ")";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new SQLException("fake driver: unsupported ResultSet." + method.getName());
            }
        };
        return (ResultSet) Proxy.newProxyInstance(
            ProbeReadOnlyFakeDriver.class.getClassLoader(),
            new Class<?>[] {ResultSet.class},
            handler
        );
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:postgresql://127.0.0.1:1/probe-readonly-fixture");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("not a real driver");
    }

    static {
        try {
            DriverManager.registerDriver(new ProbeReadOnlyFakeDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
