package com.metrixwire;

import java.util.List;
import java.util.Map;

/**
 * A tiny hand-rolled JSON writer. The agent is deliberately dependency-light
 * (only Byte Buddy), so we don't pull in Jackson/Gson. Only the subset of JSON
 * the ingest contract needs is supported: objects, lists, strings, numbers,
 * booleans and null. All methods are defensive — serialization must never throw
 * into the host app.
 */
final class Json {

    private Json() {
    }

    /** Serialize an arbitrary value understood by the writer. */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        try {
            writeValue(sb, value);
        } catch (Throwable t) {
            // Never let serialization break instrumentation.
            return "null";
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("0");
            } else {
                sb.append(v.toString());
            }
        } else if (v instanceof Number) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            writeObject(sb, (Map<Object, Object>) v);
        } else if (v instanceof List) {
            writeArray(sb, (List<Object>) v);
        } else {
            // Fallback: treat anything else as a string.
            writeString(sb, String.valueOf(v));
        }
    }

    private static void writeObject(StringBuilder sb, Map<Object, Object> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<Object> list) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, item);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
