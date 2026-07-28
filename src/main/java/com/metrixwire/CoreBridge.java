package com.metrixwire;

/**
 * Public bridge exposing the package-private recording API of {@link MetrixWire}
 * to the {@code com.metrixwire.instrument} advice package. This keeps the
 * internal API package-private (not part of the public user surface) while still
 * letting the instrumentation layer reach it. Not intended for application use.
 */
public final class CoreBridge {

    private CoreBridge() {
    }

    public static Object startTrace(String route, String method) {
        return MetrixWire.startTrace(route, method);
    }

    public static void endTrace(int status) {
        MetrixWire.endTrace(status);
    }

    public static void putTraceMeta(String key, Object value) {
        MetrixWire.putTraceMeta(key, value);
    }

    public static void recordDbQuery(String sql, long durationMs, int rowCount, String source) {
        MetrixWire.recordDbQuery(sql, durationMs, rowCount, source);
    }

    public static void recordHttpCall(String description, long durationMs, int statusCode, String source) {
        MetrixWire.recordHttpCall(description, durationMs, statusCode, source);
    }

    public static void recordTransaction(long durationMs, String source) {
        MetrixWire.recordTransaction(durationMs, source);
    }

    public static void attachException(Throwable e) {
        MetrixWire.attachException(e);
    }

    public static boolean hasActiveTrace() {
        return TraceContext.current() != null;
    }
}
