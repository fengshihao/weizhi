package com.weizhi.agent.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebViewExecToolTest {

    @Test
    public void nullReturnWithOutputPathDoesNotWriteFile() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        Path existing = ws.resolve("out.png");
        Files.write(existing, "PNG".getBytes(StandardCharsets.UTF_8));

        String json = render(ws, "out.png", "{\"result\":null}");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(o.get("ok").getAsBoolean());
        assertTrue(o.get("error").getAsString().contains("没有可落盘的返回值"));
        assertFalse(json.contains("outputBytes"));
        assertEquals("PNG", read(existing));
    }

    @Test
    public void missingReturnWithOutputPathDoesNotWriteFile() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        String json = render(ws, "puppy.png", "{}");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(o.get("ok").getAsBoolean());
        assertFalse(Files.exists(ws.resolve("puppy.png")));
    }

    @Test
    public void stringNullIsSpillableAndTypedString() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        String json = render(ws, "out.png", "{\"result\":\"null\"}");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertEquals("string", o.get("resultType").getAsString());
        assertEquals("null", o.get("resultPreview").getAsString());
        assertEquals(4, o.get("outputBytes").getAsInt());
        assertEquals("null", read(ws.resolve("out.png")));
    }

    @Test
    public void nullWithoutOutputPathIsOkWithResultTypeNull() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        String json = render(ws, null, "{\"result\":null}");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertEquals("null", o.get("resultType").getAsString());
        assertEquals("null", o.get("resultPreview").getAsString());
        assertFalse(o.has("outputPath"));
    }

    @Test
    public void falsyValuesStillSpill() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        JsonObject zero = JsonParser.parseString(render(ws, "n.txt", "{\"result\":0}")).getAsJsonObject();
        assertEquals("number", zero.get("resultType").getAsString());
        assertEquals("0", read(ws.resolve("n.txt")));

        JsonObject no = JsonParser.parseString(render(ws, "b.txt", "{\"result\":false}")).getAsJsonObject();
        assertEquals("boolean", no.get("resultType").getAsString());
        assertEquals("false", read(ws.resolve("b.txt")));

        JsonObject empty = JsonParser.parseString(render(ws, "s.txt", "{\"result\":\"\"}")).getAsJsonObject();
        assertEquals("string", empty.get("resultType").getAsString());
        assertEquals(0, empty.get("outputBytes").getAsInt());
        assertEquals("", read(ws.resolve("s.txt")));
    }

    @Test
    public void objectAndArrayResultTypes() throws Exception {
        WebViewResult object = WebViewResult.parse("{\"result\":{\"a\":1}}");
        assertEquals("object", object.resultType);
        assertEquals("{\"a\":1}", object.text);

        WebViewResult array = WebViewResult.parse("{\"result\":[1,\"null\"]}");
        assertEquals("array", array.resultType);
        assertTrue(new String(array.spillUtf8, StandardCharsets.UTF_8).contains("\"null\""));
    }

    @Test
    public void toolDescriptionStatesUtf8TextContract() throws Exception {
        Method m = WebViewExecTool.class.getMethod("webviewExec",
                String.class, String.class, String.class, String.class, String.class);
        String desc = m.getAnnotation(Tool.class).description();
        assertTrue(desc.contains("UTF-8"));
        assertTrue(desc.contains("resultType"));
        assertTrue(desc.contains("不会把文本 null 写入文件"));

        ToolParam output = m.getParameters()[3].getAnnotation(ToolParam.class);
        assertEquals("output_path", output.name());
        assertTrue(output.description().contains("UTF-8"));
        assertTrue(output.description().contains("Base64"));
        assertTrue(output.description().contains("不会写入文本 null"));
    }

    private static String render(Path ws, String outputRel, String payload) {
        WebViewExecTool tool = new WebViewExecTool(null, new WorkspaceSandbox(ws));
        WebViewTask task = new WebViewTask("return 1;", null, null, outputRel, 1000L);
        WebViewRuntime.ExecOutcome outcome = new WebViewRuntime.ExecOutcome(
                true, payload, null, Collections.<String>emptyList(), 12L);
        return tool.renderOk(task, outcome);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
