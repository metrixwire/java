package com.metrixwire.instrument;

import net.bytebuddy.asm.Advice;

/**
 * Advice woven around {@code HttpServlet#service(ServletRequest, ServletResponse)}
 * for BOTH the {@code javax.servlet} and {@code jakarta.servlet} namespaces. It
 * opens one trace per request, derives the route from the request URI + method,
 * captures the response status, a best-effort memory delta, and any exception.
 *
 * <p>Servlet objects are accessed reflectively through {@link ServletBridge} so
 * this class carries no compile-time dependency on either servlet API — whichever
 * one the host ships works, and the agent bundles neither.
 */
public final class ServletAdvice {

    private ServletAdvice() {
    }

    /** State passed from enter to exit. */
    public static final class Ctx {
        public final Object request;
        public final Object response;
        public final long startUsedMemory;
        public final boolean opened;

        Ctx(Object request, Object response, long startUsedMemory, boolean opened) {
            this.request = request;
            this.response = response;
            this.startUsedMemory = startUsedMemory;
            this.opened = opened;
        }
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Ctx enter(@Advice.Argument(0) Object request, @Advice.Argument(1) Object response) {
        return ServletBridge.onEnter(request, response);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Ctx ctx, @Advice.Thrown Throwable thrown) {
        ServletBridge.onExit(ctx, thrown);
    }
}
