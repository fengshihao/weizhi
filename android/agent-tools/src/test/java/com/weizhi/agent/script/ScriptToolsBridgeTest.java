package com.weizhi.agent.script;

import com.weizhi.agent.tool.AgentToolkit;
import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScriptToolsBridgeTest {

    @Test
    public void dispatchToolViaCapsJson() {
        AgentToolkit tk = new AgentToolkit();
        tk.registerTool(new DemoTools());
        String out = ScriptToolsBridge.dispatchTool(
                "{\"op\":\"agent.tool\",\"name\":\"ping\",\"input\":{}}",
                tk,
                Set.of("ping"));
        assertTrue(out != null && out.contains("pong"));
    }

    @Test
    public void wrapSourceKeepsUserCodeAsAsyncEvalBody() {
        String wrapped = ScriptToolsBridge.wrapSource("return await $tools.grep({pattern:'needle'});", Set.of("grep"));
        assertTrue(wrapped.contains("globalThis.$tools"));
        assertTrue(wrapped.contains("await $tools.grep"));
        assertFalse(wrapped.contains("return await"));
        assertTrue(wrapped.contains("__caps(req)"));
        assertFalse(wrapped.contains("async () =>"));
        assertFalse(wrapped.contains("JSON.stringify"));
        String expr = ScriptToolsBridge.wrapSource("1+2", Set.of("grep"));
        assertTrue(expr.endsWith("1+2"));
        String nested = ScriptToolsBridge.wrapSource("function f(){ return 1 }\n f()", Set.of("grep"));
        assertTrue(nested.contains("return 1"));
    }

    static final class DemoTools {
        @Tool(name = "ping", description = "test", readOnly = true)
        public String ping(@ToolParam(name = "x", required = false) String x) {
            return "pong";
        }
    }
}
