package com.metrixwire.instrument;

import com.metrixwire.CoreBridge;

/**
 * The single entry point the instrumentation advice uses to talk to the
 * MetrixWire core. It delegates to {@link CoreBridge}, which exposes the
 * package-private recording API of {@code com.metrixwire.MetrixWire}. Kept as a
 * thin, fully exception-safe layer so no advice ever throws into the host app.
 */
public final class MetrixWireGateway {

    private MetrixWireGateway() {
    }

    public static Object startTrace(String route, String method) {
        try {
            return CoreBridge.startTrace(route, method);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void endTrace(int status) {
        try {
            CoreBridge.endTrace(status);
        } catch (Throwable t) {
            // swallow
        }
    }

    public static void putTraceMeta(String key, Object value) {
        try {
            CoreBridge.putTraceMeta(key, value);
        } catch (Throwable t) {
            // swallow
        }
    }

    public static void recordDbQuery(String sql, long durationMs, int rowCount, String source) {
        try {
            CoreBridge.recordDbQuery(sql, durationMs, rowCount, source);
        } catch (Throwable t) {
            // swallow
        }
    }

    public static void recordHttpCall(String description, long durationMs, int statusCode, String source) {
        try {
            CoreBridge.recordHttpCall(description, durationMs, statusCode, source);
        } catch (Throwable t) {
            // swallow
        }
    }

    public static void recordTransaction(long durationMs, String source) {
        try {
            CoreBridge.recordTransaction(durationMs, source);
        } catch (Throwable t) {
            // swallow
        }
    }

    public static void attachException(Throwable e) {
        try {
            CoreBridge.attachException(e);
        } catch (Throwable t) {
            // swallow
        }
    }

    public static boolean hasActiveTrace() {
        try {
            return CoreBridge.hasActiveTrace();
        } catch (Throwable t) {
            return false;
        }
    }
}
