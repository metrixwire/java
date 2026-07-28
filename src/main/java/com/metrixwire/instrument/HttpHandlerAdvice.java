package com.metrixwire.instrument;

import net.bytebuddy.asm.Advice;

/**
 * Advice woven around {@code com.sun.net.httpserver.HttpHandler#handle(HttpExchange)}.
 * The JDK ships a lightweight embedded HTTP server ({@code com.sun.net.httpserver})
 * that isn't a Servlet container, so the servlet advice never sees it. Instrumenting
 * the handler entry point opens one trace per request for any app built directly on
 * that server — keeping the zero-config promise for the framework-less "native" case.
 *
 * <p>Route and status are read reflectively from the {@code HttpExchange} via
 * {@link HttpHandlerBridge}, so there's no compile-time dependency on the
 * {@code com.sun.*} types.
 */
public final class HttpHandlerAdvice {

    private HttpHandlerAdvice() {
    }

    public static final class Ctx {
        public final Object exchange;
        public final long startUsedMemory;
        public final boolean opened;

        Ctx(Object exchange, long startUsedMemory, boolean opened) {
            this.exchange = exchange;
            this.startUsedMemory = startUsedMemory;
            this.opened = opened;
        }
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Ctx enter(@Advice.Argument(0) Object exchange) {
        return HttpHandlerBridge.onEnter(exchange);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Ctx ctx, @Advice.Thrown Throwable thrown) {
        HttpHandlerBridge.onExit(ctx, thrown);
    }
}
