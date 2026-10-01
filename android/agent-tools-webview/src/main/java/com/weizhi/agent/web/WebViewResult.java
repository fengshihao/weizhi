package com.weizhi.agent.web;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;

/**
 * webview_exec 的 bridge payload → 回执字段（纯 JVM 可单测）。
 *
 * <p>JSON {@code null}（脚本 {@code return null} 或没有 return，async 函数 resolve 成
 * {@code undefined} 后由 bridge 收成 null）没有可落盘的字节。字符串 {@code "null"} 是
 * {@code string}，两者靠 {@link #resultType} 区分。
 */
final class WebViewResult {

    private static final Gson GSON = new Gson();

    /** {@code null|string|number|boolean|object|array}；解析失败时为 null。 */
    final String resultType;
    /** 预览原文。JSON null 的预览是文本 {@code null}，与字符串 {@code "null"} 的预览相同。 */
    final String text;
    /** 返回值的 UTF-8；JSON null 时为 null，不落盘。 */
    final byte[] spillUtf8;
    final boolean unserializable;
    /** 非 null 表示 payload 无法解析。 */
    final String parseError;

    private WebViewResult(String resultType, String text, byte[] spillUtf8,
                           boolean unserializable, String parseError) {
        this.resultType = resultType;
        this.text = text;
        this.spillUtf8 = spillUtf8;
        this.unserializable = unserializable;
        this.parseError = parseError;
    }

    static WebViewResult parse(String payloadJson) {
        try {
            JsonElement root = JsonParser.parseString(payloadJson);
            if (root == null || !root.isJsonObject()) {
                return error("payload 不是 JSON 对象");
            }
            JsonObject payload = root.getAsJsonObject();
            boolean unserializable = flag(payload, "unserializable");
            if (!payload.has("result") || payload.get("result").isJsonNull()) {
                return new WebViewResult("null", "null", null, unserializable, null);
            }
            JsonElement result = payload.get("result");
            if (result.isJsonPrimitive()) {
                JsonPrimitive p = result.getAsJsonPrimitive();
                if (p.isString()) {
                    String s = p.getAsString();
                    return new WebViewResult("string", s, utf8(s), unserializable, null);
                }
                if (p.isBoolean()) {
                    return typed("boolean", GSON.toJson(result), unserializable);
                }
                if (p.isNumber()) {
                    return typed("number", GSON.toJson(result), unserializable);
                }
            }
            if (result.isJsonArray()) {
                return typed("array", GSON.toJson(result), unserializable);
            }
            if (result.isJsonObject()) {
                return typed("object", GSON.toJson(result), unserializable);
            }
            return error("无法识别的结果类型");
        } catch (RuntimeException e) {
            return error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 图片 Base64 文本（PNG / JPEG / GIF / WebP 的常见开头）。
     * 只看文本前缀，不解码；data URL 前缀不在此列，调用方应 return 逗号后的 payload。
     */
    static boolean isImageBase64(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return t.startsWith("iVBORw0KGgo")
                || t.startsWith("/9j/")
                || t.startsWith("R0lGOD")
                || t.startsWith("UklGR");
    }

    private static WebViewResult typed(String type, String jsonText, boolean unserializable) {
        return new WebViewResult(type, jsonText, utf8(jsonText), unserializable, null);
    }

    private static WebViewResult error(String msg) {
        return new WebViewResult(null, "", null, false, msg);
    }

    private static boolean flag(JsonObject payload, String name) {
        if (!payload.has(name) || !payload.get(name).isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive p = payload.get(name).getAsJsonPrimitive();
        return p.isBoolean() && p.getAsBoolean();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
