package com.weizhi.agent.skill;

import java.util.Collections;
import java.util.List;

public final class Skill {

    private final String id;
    private final String description;
    private final String rootDir;
    private final String instructions;
    private final List<String> resources;

    public Skill(String id, String description, String rootDir,
                 String instructions, List<String> resources) {
        this.id = id;
        this.description = description;
        this.rootDir = rootDir;
        this.instructions = instructions;
        this.resources = resources == null ? Collections.emptyList() : resources;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public String getRootDir() {
        return rootDir;
    }

    public String getInstructions() {
        return instructions;
    }

    public List<String> getResources() {
        return resources;
    }
}
