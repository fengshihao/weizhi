package tests.java;

import com.weizhi.WeizhiEngine;
import com.weizhi.desktop.DesktopCaps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses {@code docs/api-cards.jsonl} and runs every card whose entry can succeed
 * on the desktop Caps host. Android-only cards are schema-checked here and
 * executed in {@code CapsInstrumentedTest}. Office cards live in Agent1.
 */
public final class ApiCardsTest {
    private static final int MAX_ENTRY_LINES = 20;
    private static final String[] REQUIRED = {
            "id", "module", "title", "summary", "tags", "platforms", "entry"
    };

    public static void main(String[] args) throws Exception {
        String platform = DesktopCaps.platformObjectName();
        if (platform == null) {
            throw new AssertionError("desktop platform required");
        }
        Path jsonl = Path.of(System.getProperty("user.dir"), "docs", "api-cards.jsonl");
        if (!Files.isRegularFile(jsonl)) {
            throw new AssertionError("missing " + jsonl);
        }
        List<String> lines = Files.readAllLines(jsonl, StandardCharsets.UTF_8);
        List<Map<String, Object>> cards = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            Object parsed;
            try {
                parsed = Json.parse(line);
            } catch (IllegalArgumentException e) {
                throw new AssertionError("line " + (i + 1) + ": " + e.getMessage());
            }
            if (!(parsed instanceof Map)) {
                throw new AssertionError("line " + (i + 1) + " is not an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> card = (Map<String, Object>) parsed;
            validate(card, i + 1);
            String id = str(card, "id");
            if (!ids.add(id)) {
                throw new AssertionError("duplicate id " + id);
            }
            cards.add(card);
        }
        if (cards.isEmpty()) {
            throw new AssertionError("api-cards.jsonl is empty");
        }
        Path scriptDir = Path.of(System.getProperty("user.dir"), "assets", "office");
        int ran = 0;
        int skipped = 0;
        for (Map<String, Object> card : cards) {
            if (androidOnly(card)) {
                skipped++;
                continue;
            }
            runCard(platform, scriptDir, card);
            ran++;
        }
        System.out.println("API cards OK (" + cards.size() + ", ran " + ran + ", android-only " + skipped + ")");
    }

    private static void validate(Map<String, Object> card, int lineNo) {
        for (String key : REQUIRED) {
            if (!card.containsKey(key)) {
                throw new AssertionError("line " + lineNo + " missing " + key);
            }
        }
        String id = str(card, "id");
        if (!id.matches("[a-z][A-Za-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*)+")) {
            throw new AssertionError("bad id " + id);
        }
        String module = str(card, "module");
        if (module.startsWith("./")) {
            if (!module.endsWith(".js") || module.contains("..") || module.indexOf('/', 2) >= 0) {
                throw new AssertionError(id + " module " + module);
            }
            Path script = Path.of(System.getProperty("user.dir"), "assets", "office", module.substring(2));
            if (!Files.isRegularFile(script)) {
                throw new AssertionError(id + " missing " + script);
            }
        } else if (!module.equals("android") && !module.equals("mac") && !module.equals("linux")) {
            throw new AssertionError(id + " module " + module);
        }
        if (str(card, "title").isBlank() || str(card, "summary").isBlank()) {
            throw new AssertionError(id + " empty title or summary");
        }
        List<String> tags = stringList(card, "tags");
        if (tags.isEmpty()) {
            throw new AssertionError(id + " needs tags");
        }
        List<String> platforms = stringList(card, "platforms");
        if (platforms.isEmpty()) {
            throw new AssertionError(id + " needs platforms");
        }
        boolean any = platforms.contains("any");
        for (String platform : platforms) {
            if (!platform.equals("any") && !platform.equals("android") && !platform.equals("desktop")) {
                throw new AssertionError(id + " platform " + platform);
            }
            if (any && !platform.equals("any")) {
                throw new AssertionError(id + " mixes any with " + platform);
            }
        }
        String entry = str(card, "entry");
        int entryLines = entry.split("\n", -1).length;
        if (entry.isBlank() || entryLines > MAX_ENTRY_LINES) {
            throw new AssertionError(id + " entry lines " + entryLines);
        }
        if (module.startsWith("./") && (entry.contains("from \"./") || entry.contains("from './"))) {
            throw new AssertionError(id + " catalog entry must use bare import from \"leaf.js\", not ./");
        }
        String blob = card.toString() + entry;
        String[] banned = {"office-js-api", "AGENT_SANDBOX", "read_file", "docs/", "详见"};
        for (String needle : banned) {
            if (blob.contains(needle)) {
                throw new AssertionError(id + " points at a long doc via " + needle);
            }
        }
        if (card.containsKey("params") && !(card.get("params") instanceof Map)) {
            throw new AssertionError(id + " params must be an object");
        }
    }

    private static boolean androidOnly(Map<String, Object> card) {
        List<String> platforms = stringList(card, "platforms");
        return platforms.size() == 1 && "android".equals(platforms.get(0));
    }

    private static void runCard(String platform, Path scriptDir, Map<String, Object> card) throws Exception {
        String id = str(card, "id");
        Path root = Files.createTempDirectory("weizhi-api-card-");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            DesktopCaps.install(engine, root, message -> true);
            if (Files.isDirectory(scriptDir)) {
                engine.setScriptFolder(scriptDir.toString());
            }
            String source = str(card, "entry");
            if (source.contains("android.") && !"android".equals(platform)) {
                source = "globalThis.android = globalThis." + platform + ";\n" + source;
            }
            String out;
            try {
                out = engine.runJs(source, 20000);
            } catch (RuntimeException e) {
                throw new AssertionError(id + " failed: " + e.getMessage(), e);
            }
            assertOutcome(root, id, out);
        }
    }

    private static void assertOutcome(Path root, String id, String out) throws IOException {
        if (out == null) {
            throw new AssertionError(id + " returned null");
        }
        switch (id) {
            case "android.files.zipCreate":
                assertContains(id, out, "Created");
                assertPk(root.resolve("bundle.zip"));
                break;
            case "android.files.zipExtract":
                assertContains(id, out, "Extracted");
                String text = Files.readString(root.resolve("unz/a.txt"), StandardCharsets.UTF_8);
                if (!"hello zip".equals(text)) {
                    throw new AssertionError(id + " extracted " + text);
                }
                break;
            default:
                if (out.isBlank()) {
                    throw new AssertionError(id + " empty result");
                }
                break;
        }
    }

    private static void assertContains(String id, String haystack, String needle) {
        if (!haystack.contains(needle)) {
            throw new AssertionError(id + " missing " + needle + " in " + haystack);
        }
    }

    private static void assertPk(Path zipPath) throws IOException {
        byte[] head = Files.readAllBytes(zipPath);
        if (head.length < 2 || head[0] != 'P' || head[1] != 'K') {
            throw new AssertionError("not a PK zip: " + zipPath);
        }
    }

    private static String str(Map<String, Object> card, String key) {
        Object value = card.get(key);
        if (!(value instanceof String)) {
            throw new AssertionError(card.get("id") + " field " + key + " must be a string");
        }
        return (String) value;
    }

    private static List<String> stringList(Map<String, Object> card, String key) {
        Object value = card.get(key);
        if (!(value instanceof List)) {
            throw new AssertionError(card.get("id") + " field " + key + " must be an array");
        }
        List<?> raw = (List<?>) value;
        List<String> out = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String) || ((String) item).isBlank()) {
                throw new AssertionError(card.get("id") + " field " + key + " needs strings");
            }
            out.add((String) item);
        }
        return out;
    }

    /** Minimal JSON parser for one object per line. */
    static final class Json {
        private final String text;
        private int i;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json parser = new Json(text);
            Object value = parser.parseValue();
            parser.skipWs();
            if (parser.i != text.length()) {
                throw new IllegalArgumentException("trailing data at " + parser.i);
            }
            return value;
        }

        private Object parseValue() {
            skipWs();
            if (i >= text.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = text.charAt(i);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' || c == 'f') {
                return parseLiteralBoolean();
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            throw new IllegalArgumentException("bad value at " + i);
        }

        private Map<String, Object> parseObject() {
            expect("{");
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(":");
                map.put(key, parseValue());
                skipWs();
                if (peek('}')) {
                    i++;
                    return map;
                }
                expect(",");
            }
        }

        private List<Object> parseArray() {
            expect("[");
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                if (peek(']')) {
                    i++;
                    return list;
                }
                expect(",");
            }
        }

        private String parseString() {
            expect("\"");
            StringBuilder sb = new StringBuilder();
            while (i < text.length()) {
                char c = text.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= text.length()) {
                    throw new IllegalArgumentException("bad escape");
                }
                char e = text.charAt(i++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(e);
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
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
                        if (i + 4 > text.length()) {
                            throw new IllegalArgumentException("bad unicode escape");
                        }
                        int cp = Integer.parseInt(text.substring(i, i + 4), 16);
                        sb.append((char) cp);
                        i += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape \\" + e);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Object parseLiteralBoolean() {
            if (text.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("bad literal at " + i);
        }

        private Number parseNumber() {
            int start = i;
            if (peek('-')) {
                i++;
            }
            while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                i++;
            }
            if (i < text.length() && text.charAt(i) == '.') {
                i++;
                while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                    i++;
                }
            }
            String token = text.substring(start, i);
            if (token.indexOf('.') >= 0) {
                return Double.valueOf(token);
            }
            return Long.valueOf(token);
        }

        private void skipWs() {
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                    return;
                }
                i++;
            }
        }

        private boolean peek(char c) {
            return i < text.length() && text.charAt(i) == c;
        }

        private void expect(String token) {
            if (!text.startsWith(token, i)) {
                throw new IllegalArgumentException("expected " + token + " at " + i);
            }
            i += token.length();
        }
    }
}
