package com.metrixwire.instrument;

import net.bytebuddy.asm.Advice;

/**
 * Advice for outbound HTTP calls. Two shapes:
 *
 * <ul>
 *   <li>{@link UrlConnection} — {@code java.net.HttpURLConnection#getResponseCode()}
 *       (the point at which the request is actually sent), producing an
 *       http_call span with the derived "METHOD https://host/path" description
 *       and the returned status code.</li>
 *   <li>{@link JdkHttpClient} — best-effort {@code java.net.http.HttpClient#send},
 *       reading the request URI and the response status reflectively.</li>
 * </ul>
 */
public final class HttpAdvice {

    private HttpAdvice() {
    }

    /** java.net.HttpURLConnection#getResponseCode(). */
    public static final class UrlConnection {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter() {
            return JdbcBridge.active() ? System.nanoTime() : 0L;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter long start,
                                @Advice.This Object conn,
                                @Advice.Return int statusCode) {
            if (start == 0L) {
                return;
            }
            long ms = Math.round((System.nanoTime() - start) / 1_000_000.0);
            HttpBridge.recordUrlConnection(conn, statusCode, ms);
        }
    }

    /** java.net.http.HttpClient#send(HttpRequest, BodyHandler). */
    public static final class JdkHttpClient {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static long enter() {
            return JdbcBridge.active() ? System.nanoTime() : 0L;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter long start,
                                @Advice.Argument(0) Object request,
                                @Advice.Return Object response) {
            if (start == 0L) {
                return;
            }
            long ms = Math.round((System.nanoTime() - start) / 1_000_000.0);
            HttpBridge.recordJdkClient(request, response, ms);
        }
    }
}
