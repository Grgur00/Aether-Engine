package io.aetherdb.training.cache;

import java.util.Map;

/** JSON encoding for the bounded, JSON-compatible engine diagnostic snapshot. */
final class DiagnosticJson {
    private DiagnosticJson() {}
    static String encode(Object value) {
        StringBuilder result = new StringBuilder();
        append(result, value);
        return result.toString();
    }
    private static void append(StringBuilder result, Object value) {
        if (value == null || value instanceof Boolean || value instanceof Number) { result.append(value); return; }
        if (value instanceof Map<?, ?> map) {
            result.append('{');
            boolean comma = false;
            for (var item : map.entrySet()) {
                if (comma) result.append(',');
                comma = true;
                append(result, item.getKey().toString()); result.append(':'); append(result, item.getValue());
            }
            result.append('}');
        } else if (value instanceof Iterable<?> items) {
            result.append('[');
            boolean comma = false;
            for (Object item : items) {
                if (comma) result.append(',');
                comma = true; append(result, item);
            }
            result.append(']');
        } else {
            result.append('"');
            for (char c : value.toString().toCharArray()) {
                if (c == '"' || c == '\\') result.append('\\').append(c);
                else if (c < 32) result.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", (int) c));
                else result.append(c);
            }
            result.append('"');
        }
    }
}
