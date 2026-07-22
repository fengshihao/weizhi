package com.weizhi.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader for host-call payloads produced by QuickJS {@code JSON.stringify}. */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s == null ? "" : s;
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        Object v = p.parseValue();
        p.skip();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("bad argument: expected JSON object");
        }
        return (Map<String, Object>) v;
    }

    public static String str(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public static long longVal(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(v));
    }

    public static int intVal(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(v));
    }

    public static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int n = 0; n < s.length(); n++) {
            char c = s.charAt(n);
            switch (c) {
                case '"':
                case '\\':
                    sb.append('\\').append(c);
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
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
        return sb.toString();
    }

    public static String error(String message) {
        return "{\"error\":" + quote(message) + "}";
    }

    private Object parseValue() {
        skip();
        if (i >= s.length()) {
            throw new IllegalArgumentException("bad argument: truncated JSON");
        }
        char c = s.charAt(i);
        if (c == '{') {
            return parseObject();
        }
        if (c == '[') {
            return parseArray();
        }
        if (c == '"') {
            return parseString();
        }
        if (c == 't') {
            i += 4;
            return Boolean.TRUE;
        }
        if (c == 'f') {
            i += 5;
            return Boolean.FALSE;
        }
        if (c == 'n') {
            i += 4;
            return null;
        }
        return parseNumber();
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        skip();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return map;
        }
        while (i < s.length()) {
            skip();
            String key = parseString();
            skip();
            if (i >= s.length() || s.charAt(i) != ':') {
                throw new IllegalArgumentException("bad argument: JSON object");
            }
            i++;
            map.put(key, parseValue());
            skip();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                break;
            }
            throw new IllegalArgumentException("bad argument: JSON object");
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        i++;
        skip();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return list;
        }
        while (i < s.length()) {
            list.add(parseValue());
            skip();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                break;
            }
            throw new IllegalArgumentException("bad argument: JSON array");
        }
        return list;
    }

    private String parseString() {
        if (i >= s.length() || s.charAt(i) != '"') {
            throw new IllegalArgumentException("bad argument: JSON string");
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("bad argument: JSON unicode");
                        }
                        int cp = Integer.parseInt(s.substring(i, i + 4), 16);
                        sb.append((char) cp);
                        i += 4;
                        break;
                    default:
                        sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("bad argument: unterminated JSON string");
    }

    private Number parseNumber() {
        int start = i;
        if (s.charAt(i) == '-') {
            i++;
        }
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == 'e'
                || s.charAt(i) == 'E' || s.charAt(i) == '+' || s.charAt(i) == '-')) {
            if ((s.charAt(i) == '+' || s.charAt(i) == '-') && i != start) {
                char prev = s.charAt(i - 1);
                if (prev != 'e' && prev != 'E') {
                    break;
                }
            }
            i++;
        }
        String num = s.substring(start, i);
        if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
            return Double.valueOf(num);
        }
        return Long.valueOf(num);
    }

    private void skip() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}
