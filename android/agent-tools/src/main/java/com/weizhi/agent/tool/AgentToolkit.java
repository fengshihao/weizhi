package com.weizhi.agent.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量工具容器：注册、schema 导出、按名调用（供宿主 LLM SDK 对接）。
 */
public final class AgentToolkit {

    private final Map<String, ReflectiveTool> tools = new LinkedHashMap<>();
    /** {@code run_js} 脚本内 {@code $tools} 可调用的工具名（默认空 = 不注入桥）。 */
    private final Set<String> jsExposed = new LinkedHashSet<>();

    public void registerTool(Object annotatedInstance) {
        for (ReflectiveTool t : ReflectiveTool.from(annotatedInstance)) {
            tools.put(t.getName(), t);
        }
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public String call(String name, Map<String, Object> input) {
        ReflectiveTool tool = tools.get(name);
        if (tool == null) {
            return "Error: unknown tool: " + name;
        }
        Map<String, Object> args = input == null ? Map.of() : input;
        return tool.call(args);
    }

    /** OpenAI / Anthropic 风格的 tools 列表（name + description + input_schema）。 */
    public List<Map<String, Object>> exportSchemas() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReflectiveTool t : tools.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", t.getName());
            item.put("description", t.getDescription());
            item.put("input_schema", t.getParameters());
            out.add(item);
        }
        return out;
    }

    public List<String> toolNames() {
        return new ArrayList<>(tools.keySet());
    }

    public void setJsExposed(Collection<String> names) {
        jsExposed.clear();
        if (names != null) {
            jsExposed.addAll(names);
        }
    }

    public void addJsExposed(String name) {
        if (name != null && !name.isEmpty()) {
            jsExposed.add(name);
        }
    }

    public Set<String> jsExposed() {
        return Collections.unmodifiableSet(jsExposed);
    }
}
