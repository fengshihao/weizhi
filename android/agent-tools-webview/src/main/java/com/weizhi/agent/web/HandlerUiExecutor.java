package com.weizhi.agent.web;

import android.os.Handler;
import android.os.Looper;

public final class HandlerUiExecutor implements UiExecutor {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void execute(Runnable runnable) {
        if (Looper.myLooper() == handler.getLooper()) {
            runnable.run();
        } else {
            handler.post(runnable);
        }
    }

    public void schedule(Runnable runnable, long delayMs) {
        handler.postDelayed(runnable, delayMs);
    }

    public void cancel(Runnable runnable) {
        handler.removeCallbacks(runnable);
    }
}
