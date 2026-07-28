package com.metrixwire.instrument;

/**
 * Shared logic for JDBC advice. Turns the raw execute return value into a
 * db_query span with a best-effort rowCount, and handles transaction
 * begin/commit timing. All methods are exception-safe.
 */
public final class JdbcBridge {

    private JdbcBridge() {
    }

    /** Only bother if there's a request trace to attach the span to. */
    public static boolean active() {
        return MetrixWireGateway.hasActiveTrace();
    }

    /**
     * Record a completed statement execution.
     *
     * @param sql        the SQL text (from the arg, or the remembered prepared SQL)
     * @param durationMs elapsed time
     * @param returned   the method's return value: a ResultSet, a Boolean
     *                   (execute), an Integer (executeUpdate) or an int[]
     *                   (executeBatch) — used to derive rowCount best-effort
     * @param statement  the Statement, used to read getUpdateCount when needed
     * @param source     a "Class.java:line" source location, or null
     */
    public static void recordExecution(String sql, long durationMs, Object returned, Object statement, String source) {
        try {
            if (sql == null || sql.isEmpty()) {
                return;
            }
            int rowCount = rowCount(returned, statement);
            MetrixWireGateway.recordDbQuery(sql.trim(), durationMs, rowCount, source);
        } catch (Throwable t) {
            // swallow
        }
    }

    private static int rowCount(Object returned, Object statement) {
        try {
            if (returned instanceof Integer) {
                // executeUpdate returns the affected row count directly.
                int n = (Integer) returned;
                return n >= 0 ? n : -1;
            }
            if (returned instanceof int[]) {
                int sum = 0;
                for (int n : (int[]) returned) {
                    if (n > 0) {
                        sum += n;
                    }
                }
                return sum;
            }
            // ResultSet (executeQuery) or Boolean (execute): try to count rows by
            // walking a scrollable ResultSet without disturbing the app's cursor;
            // if that's not safe, fall back to getUpdateCount for DML.
            Object rs = null;
            if (isResultSet(returned)) {
                rs = returned;
            } else if (returned instanceof Boolean && statement != null) {
                Object got = invoke(statement, "getResultSet");
                if (isResultSet(got)) {
                    rs = got;
                }
            }
            if (rs != null) {
                Integer counted = countResultSet(rs);
                if (counted != null) {
                    return counted;
                }
            }
            // DML executed via execute()/no ResultSet: use the update count.
            if (statement != null) {
                Object uc = invoke(statement, "getUpdateCount");
                if (uc instanceof Integer && (Integer) uc >= 0) {
                    return (Integer) uc;
                }
            }
        } catch (Throwable t) {
            // ignore — rowCount is best-effort
        }
        return -1;
    }

    /** Count rows on a scrollable ResultSet, restoring the cursor afterwards. */
    private static Integer countResultSet(Object rs) {
        try {
            Object typeObj = invoke(rs, "getType");
            // ResultSet.TYPE_FORWARD_ONLY == 1003; can't rewind those safely.
            if (!(typeObj instanceof Integer) || (Integer) typeObj == 1003) {
                return null;
            }
            Object last = invoke(rs, "last");
            if (!(last instanceof Boolean)) {
                return null;
            }
            int count = 0;
            if ((Boolean) last) {
                Object row = invoke(rs, "getRow");
                if (row instanceof Integer) {
                    count = (Integer) row;
                }
            }
            // Restore the cursor before the first row so the app reads normally.
            invoke(rs, "beforeFirst");
            return count;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isResultSet(Object o) {
        // Match by interface name rather than a java.sql.ResultSet.class literal:
        // this helper may be loaded by the bootstrap loader, which on JDK 9+ can't
        // see the platform-module java.sql package.
        try {
            if (o == null) {
                return false;
            }
            for (Class<?> iface : allInterfaces(o.getClass())) {
                if ("java.sql.ResultSet".equals(iface.getName())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        return false;
    }

    private static java.util.Set<Class<?>> allInterfaces(Class<?> type) {
        java.util.Set<Class<?>> out = new java.util.HashSet<Class<?>>();
        Class<?> c = type;
        while (c != null) {
            for (Class<?> i : c.getInterfaces()) {
                if (out.add(i)) {
                    out.addAll(allInterfaces(i));
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    // ── transactions ───────────────────────────────────────────────────────────

    public static void onSetAutoCommit(Object connection, boolean autoCommit) {
        try {
            if (!autoCommit) {
                JdbcRegistry.beginTransaction(connection);
            } else {
                // Turning autocommit back on ends any manual transaction.
                JdbcRegistry.endTransaction(connection);
            }
        } catch (Throwable ignored) {
            // swallow
        }
    }

    public static void onCommit(Object connection, String source) {
        try {
            long ms = JdbcRegistry.endTransaction(connection);
            if (ms >= 0 && active()) {
                MetrixWireGateway.recordTransaction(ms, source);
            }
        } catch (Throwable ignored) {
            // swallow
        }
    }

    // ── source location ─────────────────────────────────────────────────────────

    /** Best-effort "Class.java:line" of the first app frame outside JDBC/agent. */
    public static String source() {
        try {
            StackTraceElement[] frames = new Throwable().getStackTrace();
            for (StackTraceElement f : frames) {
                String cn = f.getClassName();
                if (cn.startsWith("com.metrixwire")
                        || cn.startsWith("java.sql")
                        || cn.startsWith("javax.sql")
                        || cn.startsWith("com.mysql")
                        || cn.startsWith("org.postgresql")
                        || cn.startsWith("org.h2")
                        || cn.startsWith("com.zaxxer.hikari")
                        || cn.startsWith("jdk.")
                        || cn.startsWith("sun.")) {
                    continue;
                }
                String file = f.getFileName();
                if (file == null) {
                    file = simpleName(cn) + ".java";
                }
                return file + ":" + f.getLineNumber();
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        String s = dot >= 0 ? className.substring(dot + 1) : className;
        int dollar = s.indexOf('$');
        return dollar >= 0 ? s.substring(0, dollar) : s;
    }

    private static Object invoke(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
