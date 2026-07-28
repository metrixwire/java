package com.metrixwire.instrument;

/**
 * Reflection helpers for the outbound-HTTP advice. Reads request method / URL and
 * response status without a compile-time dependency on the JDK HttpClient types.
 */
public final class HttpBridge {

    private HttpBridge() {
    }

    /** HttpURLConnection: build "METHOD https://host/path" + statusCode. */
    public static void recordUrlConnection(Object conn, int statusCode, long durationMs) {
        try {
            String method = str(invoke(conn, "getRequestMethod"));
            String url = str(invoke(conn, "getURL"));
            String desc = describe(method, url);
            MetrixWireGateway.recordHttpCall(desc, durationMs, statusCode, JdbcBridge.source());
        } catch (Throwable t) {
            // swallow
        }
    }

    /** java.net.http response: read request().uri()/method() and statusCode(). */
    public static void recordJdkClient(Object request, Object response, long durationMs) {
        try {
            String method = str(invoke(request, "method"));
            String uri = str(invoke(request, "uri"));
            int status = 0;
            Object sc = invoke(response, "statusCode");
            if (sc instanceof Integer) {
                status = (Integer) sc;
            }
            String desc = describe(method, uri);
            MetrixWireGateway.recordHttpCall(desc, durationMs, status, JdbcBridge.source());
        } catch (Throwable t) {
            // swallow
        }
    }

    private static String describe(String method, String url) {
        String m = (method == null || method.isEmpty()) ? "GET" : method;
        String u = (url == null || url.isEmpty()) ? "(unknown)" : url;
        return m + " " + u;
    }

    private static Object invoke(Object target, String method) {
        try {
            if (target == null) {
                return null;
            }
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
