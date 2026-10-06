package com.weizhi.agent.script;

import com.weizhi.WeizhiEngine;
import com.weizhi.agent.tool.AgentToolkit;
import com.weizhi.platform.MiniJson;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 在 {@code run_js} 脚本内注入 {@code globalThis.$tools}，经 {@code __caps} 转发到 {@link AgentToolkit}。
 */
public final class ScriptToolsBridge {

    /** HostCall JSON op（与 caps 的 files.* 等并列）。 */
    public static final String OP = "agent.tool";

    /** 脚本内禁止直调，避免递归进 run_js。 */
    public static final String EXCLUDED_TOOL = "run_js";

    private ScriptToolsBridge() {
    }

    public static Set<String> defaultExposed(AgentToolkit toolkit) {
        Set<String> names = new LinkedHashSet<>(toolkit.toolNames());
        names.remove(EXCLUDED_TOOL);
        return names;
    }

    /** 在 caps 等 {@link WeizhiEngine#setHostCall} 之后调用，链式包装现有 HostCall。 */
    public static void chain(WeizhiEngine engine, AgentToolkit toolkit, Set<String> jsExposed) {
        if (toolkit == null || jsExposed == null || jsExposed.isEmpty()) {
            return;
        }
        WeizhiEngine.HostCall inner = engine.getHostCall();
        engine.setHostCall(argsJson -> {
            String toolResult = dispatchTool(argsJson, toolkit, jsExposed);
            if (toolResult != null) {
                return toolResult;
            }
            if (inner != null) {
                return inner.call(argsJson);
            }
            return MiniJson.error("unsupported: host call");
        });
    }

    public static String wrapSource(String userSource, Set<String> jsExposed) {
        if (jsExposed == null || jsExposed.isEmpty()) {
            return userSource;
        }
        // runJs evaluates with JS_EVAL_FLAG_ASYNC: top-level await is legal, top-level
        // return is not. Strip only a top-level return so `return await $tools...`
        // becomes the script completion value. Do not wrap another async function;
        // JSON.stringify of that Promise is "{}".
        return prelude() + "\n" + stripTopLevelReturn(userSource);
    }

    /** Drop {@code return} keywords that are not inside a function, string, or comment. */
    static String stripTopLevelReturn(String source) {
        if (source == null || source.isEmpty()) {
            return source == null ? "" : source;
        }
        StringBuilder out = new StringBuilder(source.length());
        int n = source.length();
        int i = 0;
        int depth = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                if (end < 0) {
                    out.append(source.substring(i));
                    break;
                }
                out.append(source, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) {
                    out.append(source.substring(i));
                    break;
                }
                out.append(source, i, end + 2);
                i = end + 2;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipString(source, i);
                out.append(source, i, end);
                i = end;
                continue;
            }
            if (depth == 0 && isReturnKeyword(source, i)) {
                i += "return".length();
                continue;
            }
            if (c == '{' || c == '(' || c == '[') {
                depth++;
            } else if ((c == '}' || c == ')' || c == ']') && depth > 0) {
                depth--;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isReturnKeyword(String source, int i) {
        if (!source.startsWith("return", i)) {
            return false;
        }
        if (i > 0 && isIdent(source.charAt(i - 1))) {
            return false;
        }
        int after = i + "return".length();
        return after >= source.length() || !isIdent(source.charAt(after));
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int skipString(String source, int start) {
        char quote = source.charAt(start);
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return source.length();
    }

    static String prelude() {
        return "(function(){\n"
                + "globalThis.$tools = new Proxy({}, {\n"
                + "  get: function(_, name) {\n"
                + "    return async function(input) {\n"
                + "      var req = { op: '" + OP + "', name: String(name), input: input || {} };\n"
                + "      var r = __caps(req);\n"
                + "      if (typeof r === 'string') { try { r = JSON.parse(r); } catch (e) {} }\n"
                + "      if (r && r.error) throw new Error(r.error);\n"
                + "      return (r && r.result !== undefined) ? r.result : r;\n"
                + "    };\n"
                + "  }\n"
                + "});\n"
                + "})();";
    }

    @SuppressWarnings("unchecked")
    static String dispatchTool(String argsJson, AgentToolkit toolkit, Set<String> jsExposed) {
        Map<String, Object> args;
        try {
            args = MiniJson.object(argsJson);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!OP.equals(MiniJson.str(args, "op"))) {
            return null;
        }
        String name = MiniJson.str(args, "name");
        if (name.isEmpty()) {
            return MiniJson.error("bad argument: agent.tool: name required");
        }
        if (EXCLUDED_TOOL.equals(name)) {
            return MiniJson.error("unsupported: $tools.run_js (use run_js tool at host level)");
        }
        if (!jsExposed.contains(name)) {
            return MiniJson.error("unsupported: $tools." + name + " (not in js exposed whitelist)");
        }
        Object input = args.get("input");
        Map<String, Object> map;
        if (input instanceof Map) {
            map = (Map<String, Object>) input;
        } else {
            map = Map.of();
        }
        String result = toolkit.call(name, map);
        return "{\"result\":" + MiniJson.quote(result == null ? "" : result) + "}";
    }
}
