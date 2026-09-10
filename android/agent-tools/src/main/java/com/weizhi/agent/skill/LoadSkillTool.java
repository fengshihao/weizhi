package com.weizhi.agent.skill;

import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;

public class LoadSkillTool {

    private final SkillRepository repo;

    public LoadSkillTool(SkillRepository repo) {
        this.repo = repo;
    }

    @Tool(name = "load_skill_through_path",
            description = "按路径读取 skill 的 SKILL.md 或 references 下资源。",
            readOnly = true, concurrencySafe = true)
    public String load(
            @ToolParam(name = "skillId", description = "skill 目录名") String skillId,
            @ToolParam(name = "path", description = "如 SKILL.md 或 references/x.md") String path) {
        return repo.readResource(skillId, path);
    }
}
