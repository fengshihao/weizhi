package com.weizhi.agent.web;

import android.content.Context;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * SR15 P1:assets 引导页装配——读 weizhi-web/bootstrap.html 与 weizhi-web/bridge.js,
 * 把 bridge 脚本注入 html 占位符后缓存(仅首次 IO,bootstrap 页内容进程内不变)。
 */
public final class BootstrapAssets {

    /** bootstrap.html 中 bridge.js 注入点。 */
    static final String PLACEHOLDER = "/*__BRIDGE_JS__*/";

    private volatile String cached;

    /** 加载并拼接引导页 HTML;失败抛 IllegalStateException(带原因,由 Runtime 捕获转错误串)。 */
    public String load(Context ctx) {
        String html = cached;
        if (html != null) {
            return html;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            String bridge = readAsset(ctx, "weizhi-web/bridge.js");
            String template = readAsset(ctx, "weizhi-web/bootstrap.html");
            if (!template.contains(PLACEHOLDER)) {
                throw new IllegalStateException(
                        "bootstrap.html 缺少 bridge 注入占位符 " + PLACEHOLDER);
            }
            cached = template.replace(PLACEHOLDER, bridge);
            return cached;
        }
    }

    private static String readAsset(Context ctx, String path) {
        try (InputStream in = ctx.getAssets().open(path);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(1 << 16);
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            WebLog.e("read asset failed: " + path, e);
            throw new IllegalStateException("读取内置资源失败: " + path + ": " + e.getMessage(), e);
        }
    }
}
