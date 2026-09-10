package com.weizhi;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.weizhi.agent.AgentToolsBundle;
import com.weizhi.agent.tool.AgentToolkit;
import com.weizhi.caps.AndroidCaps;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class AgentToolsInstrumentedTest {

    @Test
    public void bashPwdInWorkspace() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(ctx.getCacheDir(), "agent-tools-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());

        AgentToolkit tk = AgentToolsBundle.builder(Paths.get(ws.getAbsolutePath())).build();
        String out = tk.call("bash", Map.of("command", "pwd"));
        assertTrue(out.contains(ws.getName()) || out.contains("."));
    }

    @Test
    public void runJsArithmetic() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File ws = new File(ctx.getCacheDir(), "agent-runjs-" + System.currentTimeMillis());
        assertTrue(ws.mkdirs());

        AgentToolkit tk = AgentToolsBundle.builder(Paths.get(ws.getAbsolutePath()))
                .engineConfigure(engine -> {
                    try {
                        AndroidCaps.install(engine, new AndroidCaps.Session(ctx, ws));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .build();
        String out = tk.call("run_js", Map.of("code", "1+2", "timeout_ms", 30_000));
        assertEquals("3", out);
    }
}
