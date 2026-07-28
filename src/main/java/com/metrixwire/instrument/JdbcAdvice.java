package com.metrixwire.instrument;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice for {@code java.sql} instrumentation. Each nested class is a
 * separate advice woven onto a specific method shape:
 *
 * <ul>
 *   <li>{@link PrepareStatement} — capture the SQL a PreparedStatement is built
 *       with, on {@code Connection#prepareStatement(String, ...)}.</li>
 *   <li>{@link StatementExecuteWithSql} — {@code Statement#execute/executeQuery/
 *       executeUpdate(String)} where the SQL is the first argument.</li>
 *   <li>{@link PreparedExecuteNoArg} — {@code PreparedStatement#execute/
 *       executeQuery/executeUpdate()} where SQL comes from the registry.</li>
 *   <li>{@link ExecuteBatch} — {@code Statement#executeBatch()}.</li>
 *   <li>{@link SetAutoCommit} / {@link Commit} — transaction timing.</li>
 * </ul>
 *
 * Every advice measures elapsed time from enter to exit and swallows all errors.
 */
public final class JdbcAdvice {

    private JdbcAdvice() {
    }

    /** Connection#prepareStatement(String sql, ...) -> remember the SQL. */
    public static final class PrepareStatement {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Argument(0) String sql, @Advice.Return Object stmt) {
            if (stmt != null && sql != null) {
                JdbcRegistry.rememberSql(stmt, sql);
            }
        }
    }

    /** Statement#execute/executeQuery/executeUpdate(String sql). */
    public static final class StatementExecuteWithSql {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter() {
            return JdbcBridge.active() ? System.nanoTime() : 0L;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter long start,
                                @Advice.Argument(0) String sql,
                                @Advice.Return Object returned,
                                @Advice.This Object stmt) {
            if (start == 0L) {
                return;
            }
            long ms = Math.round((System.nanoTime() - start) / 1_000_000.0);
            JdbcBridge.recordExecution(sql, ms, returned, stmt, JdbcBridge.source());
        }
    }

    /** PreparedStatement#execute/executeQuery/executeUpdate() (no arg). */
    public static final class PreparedExecuteNoArg {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter() {
            return JdbcBridge.active() ? System.nanoTime() : 0L;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter long start,
                                @Advice.Return Object returned,
                                @Advice.This Object stmt) {
            if (start == 0L) {
                return;
            }
            long ms = Math.round((System.nanoTime() - start) / 1_000_000.0);
            String sql = JdbcRegistry.sqlFor(stmt);
            JdbcBridge.recordExecution(sql, ms, returned, stmt, JdbcBridge.source());
        }
    }

    /** Statement#executeBatch(). */
    public static final class ExecuteBatch {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter() {
            return JdbcBridge.active() ? System.nanoTime() : 0L;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter long start,
                                @Advice.Return Object returned,
                                @Advice.This Object stmt) {
            if (start == 0L) {
                return;
            }
            long ms = Math.round((System.nanoTime() - start) / 1_000_000.0);
            String sql = JdbcRegistry.sqlFor(stmt);
            if (sql == null) {
                sql = "batch execute";
            }
            JdbcBridge.recordExecution(sql, ms, returned, stmt, JdbcBridge.source());
        }
    }

    /** Connection#setAutoCommit(boolean). */
    public static final class SetAutoCommit {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void enter(@Advice.This Object connection, @Advice.Argument(0) boolean autoCommit) {
            JdbcBridge.onSetAutoCommit(connection, autoCommit);
        }
    }

    /** Connection#commit(). */
    public static final class Commit {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.This Object connection) {
            JdbcBridge.onCommit(connection, JdbcBridge.source());
        }
    }
}
