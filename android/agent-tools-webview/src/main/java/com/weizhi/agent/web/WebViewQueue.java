package com.weizhi.agent.web;

/**
 * SR15 P1:webview_exec 在途准入闸(纯 JVM 可单测)。
 *
 * <p>WebViewRuntime.execute 自身 synchronized 串行(单 WebView 实例,SR15 §2),并发请求在
 * monitor 上排队;本闸限制「已提交未完成」的在途总数,溢出立即拒绝(不等待)——防止模型
 * 并发打满把请求堆积成不可收敛的长队。
 */
public final class WebViewQueue {

    /** 在途上限(约 = 1 执行中 + 2~3 排队,SR15 "队列深度上限 3")。 */
    public static final int QUEUE_LIMIT = 3;

    private final int limit;
    private int inflight;

    public WebViewQueue() {
        this(QUEUE_LIMIT);
    }

    public WebViewQueue(int limit) {
        this.limit = limit;
    }

    /** 尝试占用一个在途名额:false=已满(调用方应返回带原因错误,不等待)。 */
    public synchronized boolean tryAcquire() {
        if (inflight >= limit) {
            return false;
        }
        inflight++;
        return true;
    }

    /** 释放名额(任务完成/失败/校验拒绝后的 finally 兜底)。 */
    public synchronized void release() {
        if (inflight > 0) {
            inflight--;
        }
    }

    /** 当前在途数(测试/状态展示用)。 */
    public synchronized int inflight() {
        return inflight;
    }
}
