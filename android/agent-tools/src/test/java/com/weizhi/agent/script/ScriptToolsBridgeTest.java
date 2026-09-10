package com.weizhi.agent.script;

import com.weizhi.agent.tool.AgentToolkit;
import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

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

    static final class DemoTools {
        @Tool(name = "ping", description = "test", readOnly = true)
        public String ping(@ToolParam(name = "x", required = false) String x) {
            return "pong";
        }
    }
}
