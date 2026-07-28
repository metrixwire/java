package com.metrixwire;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * The SDK singleton and internal recording API. The public surface is
 * intentionally tiny: the primary entry point is the {@code -javaagent} which
 * auto-instruments with no code changes. {@link #init()} exists only as an
 * optional programmatic bootstrap, and {@link #captureException} is an escape
 * hatch for frameworks that swallow their own exceptions.
 *
 * <p>Everything else ({@code startTrace}, {@code recordDbQuery}, …) is
 * package-internal — called by the Byte Buddy advice classes, never by the host
 * application. All methods are defensive: instrumentation must never throw into
 * the host app.
 */
public final class MetrixWire {

    private static volatile MetrixWire INSTANCE;

    private final Config config;
    private final Transport transport;

    private MetrixWire(Config config) {
        this.config = config;
        this.transport = new Transport(config);
    }

    // ── bootstrap ────────────────────────────────────────────────────────────

    /**
     * Programmatic bootstrap (optional). The agent normally calls this for you
     * from {@code premain}. Idempotent — a second call is a no-op.
     */
    public static synchronized void init() {
        init(Config.fromEnvironment());
    }

    static synchronized void init(Config config) {
        if (INSTANCE != null) {
            return; // already initialised
        }
        MetrixWire i = new MetrixWire(config);
        INSTANCE = i;
        if (config.enabled) {
            i.transport.start();
        }
    }

    static MetrixWire get() {
        return INSTANCE;
    }

    static boolean isEnabled() {
        MetrixWire i = INSTANCE;
        return i != null && i.config.enabled;
    }

    // ── trace lifecycle (internal, called by advice) ──────────────────────────

    /** Open a trace and bind it to the current thread. Returns null when disabled. */
    static Trace startTrace(String route, String method) {
        try {
            if (!isEnabled()) {
                return null;
            }
            long now = System.currentTimeMillis();
            Trace trace = new Trace(route, method, iso(now), System.nanoTime());
            TraceContext.set(trace);
            return trace;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Close the active trace, compute duration, set status, and enqueue it. */
    static void endTrace(int statusCode) {
        try {
            Trace trace = TraceContext.current();
            if (trace == null) {
                return;
            }
            TraceContext.clear();
            trace.durationMs = Math.round((System.nanoTime() - trace.startNanos) / 1_000_000.0);
            // captureException may already have marked it as error — keep that.
            if (statusCode >= 500) {
                trace.markError();
            }
            MetrixWire i = INSTANCE;
            if (i != null) {
                i.transport.enqueue(trace);
            }
        } catch (Throwable t) {
            // swallow
        }
    }

    /** Set a trace-level meta value on the active trace (memoryMb, responseBytes, …). */
    static void putTraceMeta(String key, Object value) {
        try {
            Trace trace = TraceContext.current();
            if (trace != null) {
                trace.putMeta(key, value);
            }
        } catch (Throwable t) {
            // swallow
        }
    }

    // ── span recording (internal, called by advice) ───────────────────────────

    /** Record a db_query span with an optional rowCount. */
    static void recordDbQuery(String sql, long durationMs, int rowCount, String source) {
        try {
            Trace trace = TraceContext.current();
            if (trace == null || sql == null || sql.isEmpty()) {
                return;
            }
            Map<String, Object> meta = null;
            if (rowCount >= 0) {
                meta = new LinkedHashMap<String, Object>();
                meta.put("rowCount", rowCount);
            }
            trace.addSpan(span("db_query", sql, durationMs, source, meta));
        } catch (Throwable t) {
            // swallow
        }
    }

    /** Record an http_call span with an optional HTTP status code. */
    static void recordHttpCall(String description, long durationMs, int statusCode, String source) {
        try {
            Trace trace = TraceContext.current();
            if (trace == null || description == null || description.isEmpty()) {
                return;
            }
            Map<String, Object> meta = null;
            if (statusCode > 0) {
                meta = new LinkedHashMap<String, Object>();
                meta.put("statusCode", statusCode);
            }
            trace.addSpan(span("http_call", description, durationMs, source, meta));
        } catch (Throwable t) {
            // swallow
        }
    }

    /** Record a DB transaction as a custom span for the long_transaction detector. */
    static void recordTransaction(long durationMs, String source) {
        try {
            Trace trace = TraceContext.current();
            if (trace == null) {
                return;
            }
            Map<String, Object> meta = new LinkedHashMap<String, Object>();
            meta.put("kind", "transaction");
            trace.addSpan(span("custom", "DB transaction", durationMs, source, meta));
        } catch (Throwable t) {
            // swallow
        }
    }

    /** Attach an exception to the active trace and flag it as an error. */
    static void attachException(Throwable e) {
        try {
            Trace trace = TraceContext.current();
            if (trace == null || e == null) {
                return;
            }
            Map<String, Object> ex = new LinkedHashMap<String, Object>();
            ex.put("type", e.getClass().getSimpleName());
            ex.put("message", e.getMessage() == null ? e.getClass().getName() : e.getMessage());
            ex.put("stack", firstStackLines(e, 8));
            trace.putMeta("exception", ex);
            trace.markError();
        } catch (Throwable t) {
            // swallow
        }
    }

    // ── public escape hatch ────────────────────────────────────────────────────

    /**
     * Escape hatch: attach an exception to the active trace. Useful in a
     * framework error handler that catches the throwable before the servlet
     * advice can see it. This is NOT a manual span API — requests, queries and
     * outbound calls are still captured automatically. Never throws.
     */
    public static void captureException(Throwable e) {
        attachException(e);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Span span(String type, String description, long durationMs, String source, Map<String, Object> meta) {
        long start = System.currentTimeMillis() - Math.max(0, durationMs);
        return new Span(type, description, iso(start), durationMs, source, meta);
    }

    /** Format an epoch-ms instant as ISO8601 UTC with millis, e.g. 2026-07-14T10:00:00.000Z. */
    static String iso(long epochMs) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(epochMs));
    }

    private static String firstStackLines(Throwable e, int max) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName());
        if (e.getMessage() != null) {
            sb.append(": ").append(e.getMessage());
        }
        StackTraceElement[] frames = e.getStackTrace();
        int limit = Math.min(max - 1, frames.length);
        for (int i = 0; i < limit; i++) {
            sb.append("\n\tat ").append(frames[i].toString());
        }
        return sb.toString();
    }
}
