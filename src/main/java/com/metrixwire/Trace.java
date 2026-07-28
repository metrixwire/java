package com.metrixwire;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One request/unit of work. Holds its spans and trace-level meta (memoryMb,
 * responseBytes, exception). Spans are appended from the request thread as it
 * runs, so the list is synchronized defensively.
 */
public final class Trace {

    String route;
    final String method;
    final String startedAt; // ISO8601
    final long startNanos;
    long durationMs;
    String status = "success";

    private final List<Span> spans = Collections.synchronizedList(new ArrayList<Span>());
    private final Map<String, Object> meta = new LinkedHashMap<String, Object>();

    Trace(String route, String method, String startedAt, long startNanos) {
        this.route = route;
        this.method = method;
        this.startedAt = startedAt;
        this.startNanos = startNanos;
    }

    void addSpan(Span span) {
        if (span != null) {
            spans.add(span);
        }
    }

    void putMeta(String key, Object value) {
        if (key != null && value != null) {
            meta.put(key, value);
        }
    }

    void markError() {
        this.status = "error";
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("route", route);
        if (method != null) {
            m.put("method", method);
        }
        m.put("startedAt", startedAt);
        m.put("durationMs", durationMs);
        m.put("status", status);
        List<Object> spanMaps = new ArrayList<Object>();
        synchronized (spans) {
            for (Span s : spans) {
                spanMaps.add(s.toMap());
            }
        }
        m.put("spans", spanMaps);
        if (!meta.isEmpty()) {
            m.put("meta", meta);
        }
        return m;
    }
}
