package com.weizhi.agent.skill;

import java.util.List;

public interface SkillRepository {

    List<Skill> list();

    Skill load(String skillId);

    String readResource(String skillId, String path);

    List<String> listResources(String skillId);
}
