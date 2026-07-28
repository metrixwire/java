package com.metrixwire;

/**
 * Immutable SDK configuration. Read from system properties
 * (-Dmetrixwire.apiKey/endpoint/enabled) with environment-variable fallbacks
 * (METRIXWIRE_KEY / METRIXWIRE_ENDPOINT / METRIXWIRE_ENABLED). Zero-config: the
 * only thing a user must supply is the API key.
 */
public final class Config {

    static final String DEFAULT_ENDPOINT = "http://localhost:3000/ingest";
    static final int FLUSH_INTERVAL_MS = 5000;
    static final int TIMEOUT_MS = 3000;
    static final int MAX_BATCH = 20;

    final String apiKey;
    final String endpoint;
    final boolean enabled;
    final int timeoutMs;
    final int flushIntervalMs;
    final int maxBatch;

    Config(String apiKey, String endpoint, boolean enabled, int timeoutMs, int flushIntervalMs, int maxBatch) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.endpoint = normalizeEndpoint(endpoint);
        // A missing key means we run in disabled mode rather than throwing.
        this.enabled = enabled && this.apiKey.length() > 0;
        this.timeoutMs = timeoutMs;
        this.flushIntervalMs = flushIntervalMs;
        this.maxBatch = maxBatch;
    }

    /** Build the config from -D system properties with env fallbacks. */
    static Config fromEnvironment() {
        String apiKey = prop("metrixwire.apiKey", "METRIXWIRE_KEY", "");
        String endpoint = prop("metrixwire.endpoint", "METRIXWIRE_ENDPOINT", DEFAULT_ENDPOINT);
        boolean enabled = parseBool(prop("metrixwire.enabled", "METRIXWIRE_ENABLED", "true"), true);
        return new Config(apiKey, endpoint, enabled, TIMEOUT_MS, FLUSH_INTERVAL_MS, MAX_BATCH);
    }

    /** Accept either a base URL or a full /ingest URL. */
    static String normalizeEndpoint(String raw) {
        String url = (raw == null || raw.trim().isEmpty()) ? DEFAULT_ENDPOINT : raw.trim();
        // Trim a single trailing slash.
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/ingest")) {
            url = url + "/ingest";
        }
        return url;
    }

    private static String prop(String sysProp, String envVar, String def) {
        try {
            String v = System.getProperty(sysProp);
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
            String e = System.getenv(envVar);
            if (e != null && !e.trim().isEmpty()) {
                return e.trim();
            }
        } catch (Throwable t) {
            // Some security managers block property/env access — fall back to default.
        }
        return def;
    }

    private static boolean parseBool(String v, boolean def) {
        if (v == null) {
            return def;
        }
        String s = v.trim().toLowerCase();
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")) {
            return true;
        }
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")) {
            return false;
        }
        return def;
    }
}
