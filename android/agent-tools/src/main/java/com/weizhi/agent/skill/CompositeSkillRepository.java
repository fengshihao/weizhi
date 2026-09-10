package com.weizhi.agent.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CompositeSkillRepository implements SkillRepository {

    private final List<SkillRepository> layers;

    public CompositeSkillRepository(SkillRepository... layers) {
        this.layers = List.of(layers);
    }

    @Override
    public List<Skill> list() {
        Map<String, Skill> byId = new LinkedHashMap<>();
        for (SkillRepository layer : layers) {
            for (Skill s : layer.list()) {
                byId.put(s.getId(), s);
            }
        }
        return new ArrayList<>(byId.values());
    }

    @Override
    public Skill load(String skillId) {
        Skill found = null;
        for (SkillRepository layer : layers) {
            Skill s = layer.load(skillId);
            if (s != null) {
                found = s;
            }
        }
        return found;
    }

    @Override
    public String readResource(String skillId, String path) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (layers.get(i).load(skillId) != null) {
                return layers.get(i).readResource(skillId, path);
            }
        }
        return "Error: skill not found: " + skillId;
    }

    @Override
    public List<String> listResources(String skillId) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (layers.get(i).load(skillId) != null) {
                return layers.get(i).listResources(skillId);
            }
        }
        return new ArrayList<>();
    }
}
