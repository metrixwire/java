package com.metrixwire.instrument;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tracks per-object JDBC state that the execute advice can't see directly:
 *
 * <ul>
 *   <li>the SQL text a {@code PreparedStatement} was created with (captured at
 *       {@code Connection#prepareStatement} and read back at execute time), and</li>
 *   <li>the open-since timestamp of a manual transaction (a {@code Connection}
 *       that called {@code setAutoCommit(false)}), so {@code commit} can be
 *       recorded as a long_transaction span.</li>
 * </ul>
 *
 * Keys are held weakly so instrumentation never keeps JDBC objects alive.
 */
public final class JdbcRegistry {

    private JdbcRegistry() {
    }

    /** PreparedStatement -> the SQL it was prepared with. */
    private static final Map<Object, String> PREPARED_SQL =
            Collections.synchronizedMap(new WeakHashMap<Object, String>());

    /** Connection -> nanoTime when its current manual transaction began. */
    private static final Map<Object, Long> TX_START =
            Collections.synchronizedMap(new WeakHashMap<Object, Long>());

    public static void rememberSql(Object preparedStatement, String sql) {
        try {
            if (preparedStatement != null && sql != null) {
                PREPARED_SQL.put(preparedStatement, sql);
            }
        } catch (Throwable ignored) {
            // swallow
        }
    }

    public static String sqlFor(Object preparedStatement) {
        try {
            return PREPARED_SQL.get(preparedStatement);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void beginTransaction(Object connection) {
        try {
            if (connection != null) {
                TX_START.put(connection, System.nanoTime());
            }
        } catch (Throwable ignored) {
            // swallow
        }
    }

    /** Returns elapsed millis since the transaction began, or -1 if none tracked. */
    public static long endTransaction(Object connection) {
        try {
            Long start = TX_START.remove(connection);
            if (start == null) {
                return -1L;
            }
            return Math.round((System.nanoTime() - start) / 1_000_000.0);
        } catch (Throwable t) {
            return -1L;
        }
    }
}
