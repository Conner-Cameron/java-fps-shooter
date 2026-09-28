package com.conner.fps.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer -- the project has no JSON dependency, and the
 * data it handles (map.json, the asset manifest, the server's WebSocket
 * messages, glTF headers) is small and well-formed. Objects parse to
 * {@code Map<String,Object>}, arrays to {@code List<Object>}, numbers to
 * {@code Double}, plus {@code String}, {@code Boolean} and {@code null}.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = new Json(text).parseValue();
        if (!(v instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) v;
    }

    // ------------------------------------------------------------ typed access helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o == null ? new ArrayList<>() : (List<Object>) o;
    }

    public static double num(Object o, double fallback) {
        return o instanceof Number ? ((Number) o).doubleValue() : fallback;
    }

    public static float[] floats(Object o) {
        List<Object> l = list(o);
        float[] out = new float[l.size()];
        for (int k = 0; k < out.length; k++) out[k] = (float) num(l.get(k), 0);
        return out;
    }

    // ------------------------------------------------------------ writer

    /** Builds an object from alternating key, value arguments (values: Number, String, Boolean, double[]/float[], or pre-built via {@link #raw}). */
    public static String obj(Object... kv) {
        StringBuilder b = new StringBuilder("{");
        for (int k = 0; k < kv.length; k += 2) {
            if (k > 0) b.append(',');
            b.append(quote((String) kv[k])).append(':');
            append(b, kv[k + 1]);
        }
        return b.append('}').toString();
    }

    private static void append(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof String) b.append(quote((String) v));
        else if (v instanceof Number || v instanceof Boolean) b.append(v);
        else if (v instanceof double[]) {
            double[] a = (double[]) v;
            b.append('[');
            for (int k = 0; k < a.length; k++) b.append(k > 0 ? "," : "").append(a[k]);
            b.append(']');
        } else if (v instanceof float[]) {
            float[] a = (float[]) v;
            b.append('[');
            for (int k = 0; k < a.length; k++) b.append(k > 0 ? "," : "").append(a[k]);
            b.append(']');
        } else b.append(v); // pre-serialized JSON
    }

    private static String quote(String str) {
        StringBuilder b = new StringBuilder("\"");
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    // ------------------------------------------------------------ parser

    private Object parseValue() {
        ws();
        char c = s.charAt(i);
        if (c == '{') return parseMap();
        if (c == '[') return parseList();
        if (c == '"') return parseString();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return parseNumber();
    }

    private Map<String, Object> parseMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String key = parseString();
            ws();
            i++; // :
            m.put(key, parseValue());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
        }
    }

    private List<Object> parseList() {
        List<Object> l = new ArrayList<>();
        i++; // [
        ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            l.add(parseValue());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
        }
    }

    private String parseString() {
        StringBuilder b = new StringBuilder();
        i++; // opening quote
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: b.append(e);
                }
            } else b.append(c);
        }
    }

    private Double parseNumber() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(start, i));
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }
}
