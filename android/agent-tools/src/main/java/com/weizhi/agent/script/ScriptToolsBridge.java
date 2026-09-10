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
        String prelude = prelude();
        return prelude + "\n;(async () => {\n" + userSource + "\n})();\n";
    }

    static String prelude() {
        return "(function(){\n"
                + "globalThis.$tools = new Proxy({}, {\n"
                + "  get: function(_, name) {\n"
                + "    return async function(input) {\n"
                + "      var req = { op: '" + OP + "', name: String(name), input: input || {} };\n"
                + "      var r = __caps(JSON.stringify(req));\n"
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
