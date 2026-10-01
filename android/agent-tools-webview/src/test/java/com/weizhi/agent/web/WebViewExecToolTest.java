package com.weizhi.agent.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.Tool;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebViewExecToolTest {

    private static final Gson GSON = new Gson();

    @Test
    public void nullReturnDoesNotWriteFile() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        String json = render(ws, "{\"result\":null}");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertEquals("null", o.get("resultType").getAsString());
        assertEquals("null", o.get("resultPreview").getAsString());
        assertFalse(o.has("outputPath"));
        assertFalse(Files.exists(ws.resolve("tmp")));
    }

    @Test
    public void missingReturnDoesNotWriteFile() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        JsonObject o = JsonParser.parseString(render(ws, "{}")).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertEquals("null", o.get("resultType").getAsString());
        assertFalse(o.has("outputPath"));
        assertFalse(Files.exists(ws.resolve("tmp")));
    }

    @Test
    public void smallStringNullStaysInline() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        JsonObject o = JsonParser.parseString(render(ws, "{\"result\":\"null\"}")).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertEquals("string", o.get("resultType").getAsString());
        assertEquals("null", o.get("resultPreview").getAsString());
        assertFalse(o.has("outputPath"));
        assertFalse(Files.exists(ws.resolve("tmp")));
    }

    @Test
    public void smallFalsyValuesStayInline() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        JsonObject zero = JsonParser.parseString(render(ws, "{\"result\":0}")).getAsJsonObject();
        assertEquals("number", zero.get("resultType").getAsString());
        assertEquals("0", zero.get("resultPreview").getAsString());
        assertFalse(zero.has("outputPath"));

        JsonObject no = JsonParser.parseString(render(ws, "{\"result\":false}")).getAsJsonObject();
        assertEquals("boolean", no.get("resultType").getAsString());
        assertEquals("false", no.get("resultPreview").getAsString());

        JsonObject empty = JsonParser.parseString(render(ws, "{\"result\":\"\"}")).getAsJsonObject();
        assertEquals("string", empty.get("resultType").getAsString());
        assertEquals("", empty.get("resultPreview").getAsString());
        assertFalse(Files.exists(ws.resolve("tmp")));
    }

    @Test
    public void over64KbSpillsToWorkspaceTmp() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        String body = repeat('a', WebViewExecTool.AUTO_SPILL_BYTES + 1);
        String json = render(ws, "{\"result\":" + GSON.toJson(body) + "}");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertEquals("string", o.get("resultType").getAsString());
        String rel = o.get("outputPath").getAsString();
        assertTrue(rel.startsWith("tmp/webview-"));
        assertTrue(rel.endsWith(".txt"));
        assertEquals(body.length(), o.get("outputBytes").getAsInt());
        assertEquals(body, read(ws.resolve(rel)));
        assertTrue(o.get("resultPreview").getAsString().startsWith(repeat('a', 32)));
        assertTrue(o.get("resultPreview").getAsString().contains("截断"));
        assertTrue(o.get("resultPreview").getAsString().length() < body.length());
    }

    @Test
    public void exactly64KbStaysInline() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        String body = repeat('b', WebViewExecTool.AUTO_SPILL_BYTES);
        JsonObject o = JsonParser.parseString(
                render(ws, "{\"result\":" + GSON.toJson(body) + "}")).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertFalse(o.has("outputPath"));
        assertEquals(body, o.get("resultPreview").getAsString());
        assertFalse(Files.exists(ws.resolve("tmp")));
    }

    @Test
    public void largeResultWithoutSandboxFailsAndWritesNothing() {
        String body = repeat('c', WebViewExecTool.AUTO_SPILL_BYTES + 1);
        WebViewExecTool tool = new WebViewExecTool(null, null);
        String json = tool.renderOk(outcome("{\"result\":" + GSON.toJson(body) + "}"));
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(o.get("ok").getAsBoolean());
        assertTrue(o.get("error").getAsString().contains("64KB"));
        assertFalse(json.contains("outputPath"));
    }

    @Test
    public void objectAndArrayResultTypes() {
        WebViewResult object = WebViewResult.parse("{\"result\":{\"a\":1}}");
        assertEquals("object", object.resultType);
        assertEquals("{\"a\":1}", object.text);

        WebViewResult array = WebViewResult.parse("{\"result\":[1,\"null\"]}");
        assertEquals("array", array.resultType);
        assertTrue(new String(array.spillUtf8, StandardCharsets.UTF_8).contains("\"null\""));
        assertNull(WebViewResult.parse("{\"result\":null}").spillUtf8);
    }

    @Test
    public void toolDescriptionDoesNotAskForOutputPath() throws Exception {
        Method m = WebViewExecTool.class.getMethod("webviewExec",
                String.class, String.class, String.class, String.class);
        String desc = m.getAnnotation(Tool.class).description();
        assertTrue(desc.contains("64KB"));
        assertTrue(desc.contains("tmp/webview-"));
        assertTrue(desc.contains("UTF-8"));
        assertTrue(desc.contains("不要指定输出路径"));
        assertFalse(desc.contains("output_path"));
        for (java.lang.reflect.Parameter p : m.getParameters()) {
            com.weizhi.agent.tool.ToolParam tp = p.getAnnotation(com.weizhi.agent.tool.ToolParam.class);
            assertFalse("output_path".equals(tp.name()));
        }
    }

    private static String render(Path ws, String payload) {
        WebViewExecTool tool = new WebViewExecTool(null, new WorkspaceSandbox(ws));
        return tool.renderOk(outcome(payload));
    }

    private static WebViewRuntime.ExecOutcome outcome(String payload) {
        return new WebViewRuntime.ExecOutcome(
                true, payload, null, Collections.<String>emptyList(), 12L);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
