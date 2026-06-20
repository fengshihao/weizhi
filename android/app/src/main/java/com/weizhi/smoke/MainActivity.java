package com.weizhi.smoke;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Host shell for instrumentation; not the test itself. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        view.setText("Weizhi Android JNI smoke host");
        view.setPadding(48, 48, 48, 48);
        setContentView(view);
    }
}
