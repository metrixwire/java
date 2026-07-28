package com.metrixwire;

/**
 * ThreadLocal holder for the trace currently in flight on this thread. Servlet
 * containers dispatch a request on a single worker thread, so binding the trace
 * to the thread lets the JDBC / HTTP advice attach their spans to the right
 * request without any plumbing through application code.
 */
final class TraceContext {

    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<Trace>();

    private TraceContext() {
    }

    static Trace current() {
        return CURRENT.get();
    }

    static void set(Trace trace) {
        CURRENT.set(trace);
    }

    static void clear() {
        CURRENT.remove();
    }
}
