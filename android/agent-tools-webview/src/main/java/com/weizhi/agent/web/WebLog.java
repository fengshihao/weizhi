package com.weizhi.agent.web;

import android.util.Log;

final class WebLog {

    private static final String TAG = "WeizhiWebView";

    private WebLog() {
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
}
