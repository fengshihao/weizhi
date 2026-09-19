package com.weizhi.agent.mcp;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/** {@link McpHttpClientFactory} 常用实现。 */
public final class McpHttpClients {

    private static final long CONNECT_TIMEOUT_SECONDS = 15;
    private static final long READ_TIMEOUT_SECONDS = 60;

    private McpHttpClients() {
    }

    /** 直连（默认）：仅设置连接/读超时。 */
    public static McpHttpClientFactory direct() {
        return config -> new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /** 所有 MCP server 共用同一 {@link OkHttpClient}（宿主自行 {@code newBuilder()} 配代理等）。 */
    public static McpHttpClientFactory shared(OkHttpClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        return config -> client;
    }
}
