package com.metrixwire;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single unit of work within a trace: {@code db_query | http_call | custom}.
 * The {@code description} carries the SQL text, or "GET https://host/path", etc.
 */
public final class Span {

    final String type;
    final String description;
    final String startedAt; // ISO8601
    final long durationMs;
    final String sourceLocation; // "Class.java:42", may be null
    final Map<String, Object> meta; // may be null

    public Span(String type, String description, String startedAt, long durationMs,
                String sourceLocation, Map<String, Object> meta) {
        this.type = type;
        this.description = description;
        this.startedAt = startedAt;
        this.durationMs = Math.max(0, durationMs);
        this.sourceLocation = sourceLocation;
        this.meta = meta;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        m.put("description", description);
        m.put("startedAt", startedAt);
        m.put("durationMs", durationMs);
        if (sourceLocation != null) {
            m.put("sourceLocation", sourceLocation);
        }
        if (meta != null && !meta.isEmpty()) {
            m.put("meta", meta);
        }
        return m;
    }
}
