package com.metrixwire.instrument;

/**
 * Reflection bridge between the servlet advice and the MetrixWire core. Kept
 * separate from {@link ServletAdvice} because advice bodies are inlined into the
 * instrumented class; this bridge is invoked as a plain static call and can use
 * reflection freely. Handles {@code javax.servlet} and {@code jakarta.servlet}
 * transparently, since {@code HttpServletRequest} exposes the same method names
 * in both namespaces.
 */
public final class ServletBridge {

    private ServletBridge() {
    }

    /** Open a trace for the incoming request. Never throws. */
    public static ServletAdvice.Ctx onEnter(Object request, Object response) {
        try {
            String method = str(invoke(request, "getMethod"));
            String uri = str(invoke(request, "getRequestURI"));
            if (uri == null || uri.isEmpty()) {
                uri = "/";
            }
            String route = (method == null ? "GET" : method) + " " + uri;
            boolean opened = MetrixWireGateway.startTrace(route, method) != null;
            long startMem = usedMemory();
            return new ServletAdvice.Ctx(request, response, startMem, opened);
        } catch (Throwable t) {
            return new ServletAdvice.Ctx(request, response, 0L, false);
        }
    }

    /** Close the trace, capturing status, memory delta, and any thrown exception. */
    public static void onExit(ServletAdvice.Ctx ctx, Throwable thrown) {
        try {
            if (ctx == null || !ctx.opened) {
                return;
            }
            if (thrown != null) {
                MetrixWireGateway.attachException(thrown);
            }
            int status = 200;
            try {
                Object s = invoke(ctx.response, "getStatus");
                if (s instanceof Integer) {
                    status = (Integer) s;
                }
            } catch (Throwable ignored) {
                // getStatus not available on very old containers — assume 200/500.
            }
            if (thrown != null && status < 500) {
                status = 500;
            }

            // Best-effort response size from Content-Length header.
            long bytes = contentLength(ctx.response);
            if (bytes > 0) {
                MetrixWireGateway.putTraceMeta("responseBytes", bytes);
            }

            // Memory delta this request touched (used-memory now minus at entry).
            long deltaBytes = usedMemory() - ctx.startUsedMemory;
            if (deltaBytes > 0) {
                long mb = Math.round(deltaBytes / (1024.0 * 1024.0));
                if (mb > 0) {
                    MetrixWireGateway.putTraceMeta("memoryMb", (int) mb);
                }
            }

            MetrixWireGateway.endTrace(status);
        } catch (Throwable t) {
            // swallow
        }
    }

    private static long contentLength(Object response) {
        try {
            Object v = invoke(response, "getHeader", new Class<?>[] { String.class }, "Content-Length");
            if (v instanceof String && !((String) v).isEmpty()) {
                return Long.parseLong((String) v);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0L;
    }

    private static long usedMemory() {
        try {
            Runtime rt = Runtime.getRuntime();
            return rt.totalMemory() - rt.freeMemory();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static Object invoke(Object target, String method) {
        return invoke(target, method, new Class<?>[0]);
    }

    /**
     * Invoke a method resolving it against the first accessible class in the
     * target's hierarchy. Servlet container request/response impls often live in
     * non-exported packages, so we must call through the public servlet-API
     * supertype (HttpServletRequest / HttpServletResponse) to avoid access errors.
     */
    private static Object invoke(Object target, String method, Class<?>[] sig, Object... args) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                return c.getMethod(method, sig).invoke(target, args);
            } catch (Throwable t) {
                // try the supertype
            }
        }
        // Fall back to interfaces (the servlet API types are interfaces).
        try {
            for (Class<?> iface : target.getClass().getInterfaces()) {
                try {
                    return iface.getMethod(method, sig).invoke(target, args);
                } catch (Throwable ignored) {
                    // next interface
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
