package com.parsv2r.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader/writer with no dependencies.
 *
 * <p>The point of this class is that it is free of both Android and org.json, so every piece of
 * configuration handling built on top of it can be compiled and run on a plain desktop JVM. Xray
 * is configured entirely with JSON, so if the JSON layer is only testable on a phone then nothing
 * above it is testable either.
 *
 * <p>Values are represented with plain Java types:
 * {@code Map<String,Object>}, {@code List<Object>}, {@code String}, {@code Double}, {@code Long},
 * {@code Boolean} and {@code null}. Object key order is preserved.
 */
public final class Json {

    private Json() {
    }

    /** Thrown when input is not valid JSON. Carries the offset so a user can be told where. */
    public static class SyntaxException extends RuntimeException {
        public final int offset;

        SyntaxException(String message, int offset) {
            super(message + " (at character " + offset + ")");
            this.offset = offset;
        }
    }

    // ---------------------------------------------------------------- parsing

    public static Object parse(String text) {
        if (text == null) throw new SyntaxException("no input", 0);
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos < p.text.length()) {
            throw new SyntaxException("unexpected trailing text", p.pos);
        }
        return value;
    }

    /** Parses and insists the result is an object, which is what every Xray config is. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new SyntaxException("expected a JSON object at the top level", 0);
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else if (c == '/' && pos + 1 < text.length()) {
                    // Xray's own sample configs are full of // and /* */ comments, and its loader
                    // accepts them. A user pasting one in should not be told it is invalid.
                    char next = text.charAt(pos + 1);
                    if (next == '/') {
                        while (pos < text.length() && text.charAt(pos) != '\n') pos++;
                    } else if (next == '*') {
                        int end = text.indexOf("*/", pos + 2);
                        if (end < 0) throw new SyntaxException("unterminated comment", pos);
                        pos = end + 2;
                    } else {
                        return;
                    }
                } else {
                    return;
                }
            }
        }

        Object readValue() {
            if (pos >= text.length()) throw new SyntaxException("unexpected end of input", pos);
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    return readLiteral("true", Boolean.TRUE);
                case 'f':
                    return readLiteral("false", Boolean.FALSE);
                case 'n':
                    return readLiteral("null", null);
                default:
                    return readNumber();
            }
        }

        Map<String, Object> readObject() {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (pos >= text.length() || text.charAt(pos) != '"') {
                    throw new SyntaxException("expected a key in double quotes", pos);
                }
                String key = readString();
                skipWhitespace();
                if (pos >= text.length() || text.charAt(pos) != ':') {
                    throw new SyntaxException("expected ':' after key \"" + key + "\"", pos);
                }
                pos++;
                skipWhitespace();
                out.put(key, readValue());
                skipWhitespace();
                if (pos >= text.length()) throw new SyntaxException("unterminated object", pos);
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    skipWhitespace();
                    // tolerate a trailing comma before }
                    if (pos < text.length() && text.charAt(pos) == '}') {
                        pos++;
                        return out;
                    }
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return out;
                }
                throw new SyntaxException("expected ',' or '}'", pos);
            }
        }

        List<Object> readArray() {
            List<Object> out = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ']') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                out.add(readValue());
                skipWhitespace();
                if (pos >= text.length()) throw new SyntaxException("unterminated array", pos);
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    skipWhitespace();
                    if (pos < text.length() && text.charAt(pos) == ']') {
                        pos++;
                        return out;
                    }
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return out;
                }
                throw new SyntaxException("expected ',' or ']'", pos);
            }
        }

        String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= text.length()) throw new SyntaxException("unterminated string", pos);
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (pos >= text.length()) throw new SyntaxException("unterminated escape", pos);
                char e = text.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > text.length()) {
                            throw new SyntaxException("truncated \\u escape", pos);
                        }
                        String hex = text.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException ex) {
                            throw new SyntaxException("bad \\u escape", pos);
                        }
                        pos += 4;
                        break;
                    default:
                        throw new SyntaxException("unknown escape \\" + e, pos);
                }
            }
        }

        Object readLiteral(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw new SyntaxException("unexpected value", pos);
            }
            pos += word.length();
            return value;
        }

        Object readNumber() {
            int start = pos;
            if (pos < text.length() && (text.charAt(pos) == '-' || text.charAt(pos) == '+')) pos++;
            boolean floating = false;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) throw new SyntaxException("unexpected character", pos);
            String raw = text.substring(start, pos);
            try {
                // Ports and timeouts read far better as integers than as 443.0.
                if (!floating) return Long.parseLong(raw);
                return Double.parseDouble(raw);
            } catch (NumberFormatException ex) {
                throw new SyntaxException("bad number \"" + raw + "\"", start);
            }
        }
    }

    // ---------------------------------------------------------------- writing

    /** Renders a value as compact JSON. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, -1, 0);
        return sb.toString();
    }

    /** Renders a value as indented JSON, which is what a human reads in the config screen. */
    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 2, 0);
        return sb.toString();
    }

    private static void write(Object value, StringBuilder sb, int indent, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Number) {
            writeNumber((Number) value, sb);
        } else if (value instanceof Map) {
            writeObject((Map<?, ?>) value, sb, indent, depth);
        } else if (value instanceof List) {
            writeArray((List<?>) value, sb, indent, depth);
        } else {
            writeString(value.toString(), sb);
        }
    }

    private static void writeNumber(Number n, StringBuilder sb) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                sb.append((long) d);
                return;
            }
        }
        sb.append(n.toString());
    }

    private static void writeObject(Map<?, ?> map, StringBuilder sb, int indent, int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            newline(sb, indent, depth + 1);
            writeString(String.valueOf(e.getKey()), sb);
            sb.append(':');
            if (indent >= 0) sb.append(' ');
            write(e.getValue(), sb, indent, depth + 1);
        }
        newline(sb, indent, depth);
        sb.append('}');
    }

    private static void writeArray(List<?> list, StringBuilder sb, int indent, int depth) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object o : list) {
            if (!first) sb.append(',');
            first = false;
            newline(sb, indent, depth + 1);
            write(o, sb, indent, depth + 1);
        }
        newline(sb, indent, depth);
        sb.append(']');
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent < 0) return;
        sb.append('\n');
        for (int i = 0; i < indent * depth; i++) sb.append(' ');
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
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

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    public static String str(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof Number) {
            writeNumberToString((Number) value);
        }
        return String.valueOf(value);
    }

    private static String writeNumberToString(Number n) {
        StringBuilder sb = new StringBuilder();
        writeNumber(n, sb);
        return sb.toString();
    }

    /** Reads a value as an int, tolerating the string form, because URIs carry ports as text. */
    public static int intOf(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static Map<String, Object> newObject() {
        return new LinkedHashMap<>();
    }

    public static List<Object> newArray() {
        return new ArrayList<>();
    }
}
