package com.weizhi.agent.web;

import android.content.Context;

import com.weizhi.agent.AgentToolsExtension;
import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.AgentToolkit;

/**
 * 可选 WebView 工具：{@code webview_exec} + {@code run_js} 内 {@code $tools.webview_exec} 白名单。
 */
public final class WebViewAgentExtension implements AgentToolsExtension {

    private final Context context;

    public WebViewAgentExtension(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void register(AgentToolkit toolkit, WorkspaceSandbox sandbox) {
        WebViewRuntime runtime = WebViewRuntime.getInstance(context, new HandlerUiExecutor());
        toolkit.registerTool(new WebViewExecTool(runtime, sandbox));
        toolkit.addJsExposed("webview_exec");
        WebLog.i("registered webview_exec");
    }
}
