// SPDX-License-Identifier: Apache-2.0
package kiosk.groundnews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader and writer, so the plugin needs no library and runs the same in tests. */
final class Json {
    private final String text;
    private int pos;

    private Json(String text, int pos) {
        this.text = text;
        this.pos = pos;
    }

    /** Parses one complete JSON value. Throws IllegalArgumentException on anything else. */
    static Object parse(String text) {
        Json json = new Json(text, 0);
        Object value = json.value(0);
        json.space();
        if (json.pos != text.length()) throw json.error("Trailing data");
        return value;
    }

    /** Reads the JSON string that starts at {@code start}, which must be a double quote. */
    static String stringAt(String text, int start) {
        return new Json(text, start).string();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + pos);
    }

    private void space() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
            pos++;
        }
    }

    private Object value(int depth) {
        if (depth > 200) throw error("Too deep");
        space();
        if (pos >= text.length()) throw error("Unexpected end");
        char c = text.charAt(pos);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default: return number();
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) throw error("Unexpected token");
        pos += word.length();
        return value;
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        space();
        if (pos < text.length() && text.charAt(pos) == '}') { pos++; return map; }
        while (true) {
            space();
            if (pos >= text.length() || text.charAt(pos) != '"') throw error("Expected key");
            String key = string();
            space();
            if (pos >= text.length() || text.charAt(pos) != ':') throw error("Expected colon");
            pos++;
            map.put(key, value(depth + 1));
            space();
            if (pos >= text.length()) throw error("Unexpected end");
            char c = text.charAt(pos++);
            if (c == '}') return map;
            if (c != ',') throw error("Expected comma");
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;
        space();
        if (pos < text.length() && text.charAt(pos) == ']') { pos++; return list; }
        while (true) {
            list.add(value(depth + 1));
            space();
            if (pos >= text.length()) throw error("Unexpected end");
            char c = text.charAt(pos++);
            if (c == ']') return list;
            if (c != ',') throw error("Expected comma");
        }
    }

    private String string() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); continue; }
            if (pos >= text.length()) break;
            char e = text.charAt(pos++);
            switch (e) {
                case '"': case '\\': case '/': out.append(e); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (pos + 4 > text.length()) throw error("Bad escape");
                    try {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("Bad escape");
                    }
                    pos += 4;
                    break;
                default: throw error("Bad escape");
            }
        }
        throw error("Unterminated string");
    }

    private Number number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        if (start == pos) throw error("Unexpected token");
        String raw = text.substring(start, pos);
        try {
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                long value = Long.parseLong(raw);
                return value;
            }
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            throw error("Bad number");
        }
    }

    /**
     * Writes maps, lists, strings, numbers, booleans and null. The output is safe inside an HTML
     * script element: it never contains a less-than sign or a line separator.
     */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            quote((String) value, out);
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) out.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) out.append((long) d);
            else out.append(d);
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            quote(value.toString(), out);
        }
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
