package com.weizhi.agent;

import android.content.Context;

import com.weizhi.WeizhiEngine;
import com.weizhi.agent.sandbox.ReadMount;
import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.skill.AssetSkillRepository;
import com.weizhi.agent.skill.CompositeSkillRepository;
import com.weizhi.agent.skill.FileSystemSkillRepository;
import com.weizhi.agent.skill.LoadSkillTool;
import com.weizhi.agent.skill.SkillRepository;
import com.weizhi.agent.script.ScriptToolsBridge;
import com.weizhi.agent.script.WeizhiRunJsTool;
import com.weizhi.agent.script.WeizhiScriptRunner;
import com.weizhi.agent.tool.AgentToolkit;
import com.weizhi.agent.tool.builtin.BashTool;
import com.weizhi.agent.tool.builtin.FileEditTools;
import com.weizhi.agent.tool.builtin.FileReadTools;
import com.weizhi.agent.tool.builtin.FileWriteTools;
import com.weizhi.agent.tool.builtin.GlobTool;
import com.weizhi.agent.tool.builtin.GrepTool;
import com.weizhi.agent.tool.builtin.ZipTools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 默认 Agent 工具环装配（文件/搜索/zip/bash/skill/run_js + 可选扩展）。
 */
public final class AgentToolsBundle {

    private AgentToolsBundle() {
    }

    public static Builder builder(Path workspace) {
        return new Builder(workspace);
    }

    public static final class Builder {
        private final Path workspace;
        private Path extraReadRoot;
        private List<ReadMount> readMounts;
        private SkillRepository skillRepository;
        private WeizhiScriptRunner scriptRunner;
        private Consumer<WeizhiEngine> engineConfigure;
        private Set<String> jsExposedOverride;
        private boolean registerRunJs = true;
        private final List<AgentToolsExtension> extensions = new ArrayList<>();

        Builder(Path workspace) {
            this.workspace = workspace.toAbsolutePath().normalize();
        }

        public Builder extraReadRoot(Path readRoot) {
            this.extraReadRoot = readRoot;
            return this;
        }

        /** 逻辑前缀只读挂载（与 {@link #extraReadRoot} 二选一；挂载优先）。 */
        public Builder readMounts(List<ReadMount> mounts) {
            this.readMounts = mounts;
            return this;
        }

        public Builder skillRepository(SkillRepository repo) {
            this.skillRepository = repo;
            return this;
        }

        /** 动态 skill：{@code workspace/skills}（可写）。 */
        public Builder defaultSkillsDir() {
            this.skillRepository = new FileSystemSkillRepository(workspace.resolve("skills"));
            return this;
        }

        /**
         * 内置 assets + 工作区动态 skill（后者覆盖同名 id）。
         *
         * @param assetRoot assets 下目录，如 {@code "agent_skills"}
         */
        public Builder compositeSkills(Context context, String assetRoot) {
            SkillRepository fs = new FileSystemSkillRepository(workspace.resolve("skills"));
            SkillRepository assets = new AssetSkillRepository(context, assetRoot);
            this.skillRepository = new CompositeSkillRepository(assets, fs);
            return this;
        }

        public Builder scriptRunner(WeizhiScriptRunner runner) {
            this.scriptRunner = runner;
            return this;
        }

        /** 未自定义 {@link #scriptRunner(WeizhiScriptRunner)} 时用于配置 {@link WeizhiEngine}（caps/fetch 等）。 */
        public Builder engineConfigure(Consumer<WeizhiEngine> configure) {
            this.engineConfigure = configure;
            return this;
        }

        /** 覆盖 {@code run_js} 脚本内 {@code $tools} 白名单；默认除 run_js 外全部已注册工具。 */
        public Builder jsExposed(Set<String> names) {
            this.jsExposedOverride = names;
            return this;
        }

        public Builder registerRunJs(boolean on) {
            this.registerRunJs = on;
            return this;
        }

        public Builder extension(AgentToolsExtension ext) {
            if (ext != null) {
                extensions.add(ext);
            }
            return this;
        }

        public AgentToolkit build() {
            AgentToolkit tk = new AgentToolkit();
            WorkspaceSandbox sandbox = readMounts != null
                    ? new WorkspaceSandbox(workspace, readMounts)
                    : new WorkspaceSandbox(workspace, extraReadRoot);

            tk.registerTool(new FileReadTools(sandbox));
            tk.registerTool(new FileWriteTools(sandbox));
            tk.registerTool(new FileEditTools(sandbox));
            tk.registerTool(new ZipTools(sandbox));
            tk.registerTool(new GrepTool(sandbox));
            tk.registerTool(new GlobTool(sandbox));
            tk.registerTool(new BashTool(sandbox));

            for (AgentToolsExtension ext : extensions) {
                ext.register(tk, sandbox);
            }

            if (skillRepository != null) {
                tk.registerTool(new LoadSkillTool(skillRepository));
            }

            WeizhiScriptRunner runner = scriptRunner;
            if (registerRunJs) {
                if (runner == null) {
                    runner = new WeizhiScriptRunner(workspace, engineConfigure);
                }
                Set<String> exposed = jsExposedOverride != null
                        ? jsExposedOverride
                        : ScriptToolsBridge.defaultExposed(tk);
                tk.setJsExposed(exposed);
                runner.attachToolBridge(tk, exposed);
                tk.registerTool(new WeizhiRunJsTool(runner));
            }

            return tk;
        }
    }
}
