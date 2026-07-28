package com.metrixwire.instrument;

/**
 * Reflection bridge for {@code com.sun.net.httpserver.HttpHandler} instrumentation.
 * Reads the request method, path and response status from the {@code HttpExchange}
 * without a compile-time dependency on the {@code com.sun.*} API.
 */
public final class HttpHandlerBridge {

    private HttpHandlerBridge() {
    }

    public static HttpHandlerAdvice.Ctx onEnter(Object exchange) {
        try {
            String method = str(invoke(exchange, "getRequestMethod"));
            String path = requestPath(exchange);
            String route = (method == null ? "GET" : method) + " " + path;
            boolean opened = MetrixWireGateway.startTrace(route, method) != null;
            return new HttpHandlerAdvice.Ctx(exchange, usedMemory(), opened);
        } catch (Throwable t) {
            return new HttpHandlerAdvice.Ctx(exchange, 0L, false);
        }
    }

    public static void onExit(HttpHandlerAdvice.Ctx ctx, Throwable thrown) {
        try {
            if (ctx == null || !ctx.opened) {
                return;
            }
            if (thrown != null) {
                MetrixWireGateway.attachException(thrown);
            }
            int status = 200;
            try {
                Object code = invoke(ctx.exchange, "getResponseCode");
                if (code instanceof Integer && (Integer) code > 0) {
                    status = (Integer) code;
                }
            } catch (Throwable ignored) {
                // ignore
            }
            if (thrown != null && status < 500) {
                status = 500;
            }

            long bytes = responseBytes(ctx.exchange);
            if (bytes > 0) {
                MetrixWireGateway.putTraceMeta("responseBytes", bytes);
            }

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

    private static String requestPath(Object exchange) {
        try {
            Object uri = invoke(exchange, "getRequestURI");
            Object path = uri == null ? null : invoke(uri, "getPath");
            String p = str(path);
            return (p == null || p.isEmpty()) ? "/" : p;
        } catch (Throwable t) {
            return "/";
        }
    }

    /** Response size from the Content-Length response header, if set. */
    private static long responseBytes(Object exchange) {
        try {
            Object headers = invoke(exchange, "getResponseHeaders");
            // Headers is a public exported type (extends HashMap), so getFirst is
            // directly accessible.
            Object v = invoke(headers, "getFirst", new Class<?>[] { String.class }, "Content-Length");
            if (v instanceof String && !((String) v).isEmpty()) {
                return Long.parseLong((String) v);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return 0L;
    }

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
        return null;
    }

    private static long usedMemory() {
        try {
            Runtime rt = Runtime.getRuntime();
            return rt.totalMemory() - rt.freeMemory();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Invoke a no-arg method, resolving it against the first accessible (public,
     * exported) class in the target's hierarchy. The concrete exchange impl lives in
     * the non-exported {@code sun.net.httpserver} package, so reflecting on it
     * directly throws IllegalAccessException on the module path — we must call
     * through the public {@code com.sun.net.httpserver.HttpExchange} supertype.
     */
    private static Object invoke(Object target, String method) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                return c.getMethod(method).invoke(target);
            } catch (Throwable t) {
                // try the supertype (may be an accessible, exported class)
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
