package com.weizhi.agent.web;

import com.google.gson.Gson;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebViewExecToolTest {

    private static final Gson GSON = new Gson();
    /** 1x1 PNG 的 Base64，远小于 64KB。 */
    private static final String PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @Test
    public void smallPngBase64SpillsWithoutPayloadInPreview() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        JsonObject o = JsonParser.parseString(render(ws, null, jsonString(PNG_B64))).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertEquals("string", o.get("resultType").getAsString());
        String rel = o.get("outputPath").getAsString();
        assertTrue(rel.startsWith("tmp/webview_exec/"));
        assertTrue(rel.endsWith(".b64"));
        assertEquals(PNG_B64.length(), o.get("outputBytes").getAsInt());
        assertEquals(PNG_B64, read(ws.resolve(rel)));
        assertEquals(WebViewExecTool.SPILL_PREVIEW, o.get("resultPreview").getAsString());
        assertFalse(o.get("resultPreview").getAsString().contains("iVBORw0KGgo"));
    }

    @Test
    public void nullReturnDoesNotWriteEvenIfPathGiven() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        Path existing = ws.resolve("puppy.png");
        Files.write(existing, "PNG".getBytes(StandardCharsets.UTF_8));

        JsonObject withPath = JsonParser.parseString(render(ws, "puppy.png", "{\"result\":null}")).getAsJsonObject();
        assertFalse(withPath.get("ok").getAsBoolean());
        assertTrue(withPath.get("error").getAsString().contains("null"));
        assertFalse(withPath.has("outputPath"));
        assertEquals("PNG", read(existing));

        JsonObject missing = JsonParser.parseString(render(ws, null, "{}")).getAsJsonObject();
        assertFalse(missing.get("ok").getAsBoolean());
        assertFalse(Files.exists(ws.resolve("tmp")));
    }

    @Test
    public void explicitOutputPathOverridesTempFile() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        JsonObject o = JsonParser.parseString(render(ws, "puppy.png", jsonString(PNG_B64))).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertEquals("puppy.png", o.get("outputPath").getAsString());
        assertEquals(PNG_B64, read(ws.resolve("puppy.png")));
        assertEquals(WebViewExecTool.SPILL_PREVIEW, o.get("resultPreview").getAsString());
        assertFalse(Files.exists(ws.resolve("tmp")));
    }

    @Test
    public void otherImagePrefixesSpill() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        assertSpillsImage(ws, "/9j/small-jpeg");
        assertSpillsImage(ws, "R0lGODlhAQAB");
        assertSpillsImage(ws, "UklGRgAAA");
    }

    @Test
    public void smallNonImageStaysInline() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        JsonObject text = JsonParser.parseString(render(ws, null, "{\"result\":\"null\"}")).getAsJsonObject();
        assertTrue(text.get("ok").getAsBoolean());
        assertEquals("string", text.get("resultType").getAsString());
        assertEquals("null", text.get("resultPreview").getAsString());
        assertFalse(text.has("outputPath"));

        JsonObject zero = JsonParser.parseString(render(ws, null, "{\"result\":0}")).getAsJsonObject();
        assertEquals("number", zero.get("resultType").getAsString());
        assertEquals("0", zero.get("resultPreview").getAsString());
        assertFalse(zero.has("outputPath"));
        assertFalse(Files.exists(ws.resolve("tmp")));
    }

    @Test
    public void over64KbSpillsAndPreviewOmitsPayload() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        String body = repeat('a', WebViewExecTool.AUTO_SPILL_BYTES + 1);
        JsonObject o = JsonParser.parseString(render(ws, null, jsonString(body))).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        String rel = o.get("outputPath").getAsString();
        assertTrue(rel.startsWith("tmp/webview_exec/"));
        assertEquals(body, read(ws.resolve(rel)));
        assertEquals(WebViewExecTool.SPILL_PREVIEW, o.get("resultPreview").getAsString());
        assertFalse(o.get("resultPreview").getAsString().contains("aaa"));
    }

    @Test
    public void exactly64KbNonImageStaysInline() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-wv");
        String body = repeat('b', WebViewExecTool.AUTO_SPILL_BYTES);
        JsonObject o = JsonParser.parseString(render(ws, null, jsonString(body))).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertFalse(o.has("outputPath"));
        assertEquals(body, o.get("resultPreview").getAsString());
    }

    @Test
    public void objectAndArrayResultTypes() {
        WebViewResult object = WebViewResult.parse("{\"result\":{\"a\":1}}");
        assertEquals("object", object.resultType);
        assertEquals("{\"a\":1}", object.text);
        assertTrue(WebViewResult.isImageBase64(PNG_B64));
        assertFalse(WebViewResult.isImageBase64("hello"));

        WebViewResult array = WebViewResult.parse("{\"result\":[1,\"null\"]}");
        assertEquals("array", array.resultType);
        assertTrue(new String(array.spillUtf8, StandardCharsets.UTF_8).contains("\"null\""));
        assertNull(WebViewResult.parse("{\"result\":null}").spillUtf8);
    }

    @Test
    public void toolDescriptionDocumentsAutoSpill() throws Exception {
        Method m = WebViewExecTool.class.getMethod("webviewExec",
                String.class, String.class, String.class, String.class, String.class);
        String desc = m.getAnnotation(Tool.class).description();
        assertTrue(desc.contains("tmp/webview_exec/"));
        assertTrue(desc.contains("不含 Base64"));
        assertTrue(desc.contains("output_path"));

        ToolParam output = m.getParameters()[3].getAnnotation(ToolParam.class);
        assertEquals("output_path", output.name());
        assertFalse(output.required());
        assertTrue(output.description().contains("可选"));
        assertTrue(output.description().contains("UTF-8"));
    }

    private static void assertSpillsImage(Path ws, String b64) throws Exception {
        JsonObject o = JsonParser.parseString(render(ws, null, jsonString(b64))).getAsJsonObject();
        assertTrue(o.get("ok").getAsBoolean());
        assertTrue(o.get("outputPath").getAsString().startsWith("tmp/webview_exec/"));
        assertEquals(WebViewExecTool.SPILL_PREVIEW, o.get("resultPreview").getAsString());
        assertEquals(b64, read(ws.resolve(o.get("outputPath").getAsString())));
    }

    private static String jsonString(String value) {
        return "{\"result\":" + GSON.toJson(value) + "}";
    }

    private static String render(Path ws, String outputRel, String payload) {
        WebViewExecTool tool = new WebViewExecTool(null, new WorkspaceSandbox(ws));
        return tool.renderOk(outcome(payload), outputRel);
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
