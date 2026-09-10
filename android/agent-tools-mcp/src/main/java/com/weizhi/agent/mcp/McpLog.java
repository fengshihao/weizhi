package com.weizhi.agent.mcp;

import android.util.Log;

final class McpLog {

    private static final String TAG = "WeizhiMcp";

    private McpLog() {
    }

    static void i(String msg) {
        Log.i(TAG, msg);
    }

    static void w(String msg) {
        Log.w(TAG, msg);
    }

    static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }

    static void e(String msg) {
        Log.e(TAG, msg);
    }
}
