package com.weizhi.agent.web;

/** 主线程调度（WebView 必须在 UI 线程）。 */
public interface UiExecutor {

    void execute(Runnable runnable);
}
