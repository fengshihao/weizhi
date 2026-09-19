package com.weizhi.agent.mcp;

import okhttp3.OkHttpClient;

/**
 * 为 {@link McpClient} 提供 {@link OkHttpClient}。宿主可在此配置代理、证书、拦截器等；
 * 本模块不读取 {@code https_proxy} 等环境变量。
 */
public interface McpHttpClientFactory {

    OkHttpClient create(McpServerConfig config);
}
