package com.weizhi.agent.script;

import com.weizhi.WeizhiEngine;
import com.weizhi.agent.tool.AgentToolkit;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 每调用一次脚本：打开引擎 → 配置 →（可选）$tools 桥 → {@link WeizhiEngine#runJs} → 关闭。
 */
public final class WeizhiScriptRunner {

    private final Path workspace;
    private final Consumer<WeizhiEngine> configure;
    private AgentToolkit toolBridgeToolkit;
    private Set<String> jsExposed;

    public WeizhiScriptRunner(Path workspace, Consumer<WeizhiEngine> configure) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.configure = configure;
    }

    public WeizhiScriptRunner(Path workspace) {
        this(workspace, null);
    }

    /** 在 {@link #run} 时链式注入 {@code globalThis.$tools}（须在 caps 安装之后生效）。 */
    public void attachToolBridge(AgentToolkit toolkit, Set<String> exposedToolNames) {
        this.toolBridgeToolkit = toolkit;
        this.jsExposed = exposedToolNames;
    }

    public String run(String source, int timeoutMs) {
        try (WeizhiEngine engine = new WeizhiEngine()) {
            engine.setFsRoot(workspace.toString());
            if (configure != null) {
                configure.accept(engine);
            }
            if (toolBridgeToolkit != null && jsExposed != null && !jsExposed.isEmpty()) {
                ScriptToolsBridge.chain(engine, toolBridgeToolkit, jsExposed);
            }
            String wrapped = ScriptToolsBridge.wrapSource(source, jsExposed);
            return engine.runJs(wrapped, timeoutMs);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
