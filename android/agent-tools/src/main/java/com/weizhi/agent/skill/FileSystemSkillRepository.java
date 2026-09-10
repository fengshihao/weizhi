package com.weizhi.agent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class FileSystemSkillRepository implements SkillRepository {

    private final Path root;

    public FileSystemSkillRepository(Path root) {
        this.root = root;
    }

    @Override
    public List<Skill> list() {
        List<Skill> all = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return all;
        }
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).forEach(d -> {
                if (Files.exists(d.resolve("SKILL.md"))) {
                    try {
                        Skill sk = MarkdownSkillParser.parse(d);
                        all.add(new Skill(sk.getId(), sk.getDescription(),
                                sk.getRootDir(), "", sk.getResources()));
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (IOException ignored) {
        }
        return all;
    }

    @Override
    public Skill load(String skillId) {
        if (skillId == null || skillId.isEmpty() || skillId.startsWith("/") || skillId.contains("..")) {
            return null;
        }
        Path d = root.resolve(skillId).normalize();
        if (!d.startsWith(root.normalize())) {
            return null;
        }
        if (!Files.exists(d.resolve("SKILL.md"))) {
            return null;
        }
        try {
            return MarkdownSkillParser.parse(d);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String readResource(String skillId, String path) {
        if (path == null || path.equals(".") || path.equals("./") || path.startsWith("/")) {
            return "Error: path must be a relative file (e.g. 'SKILL.md'), got: " + path;
        }
        Skill sk = load(skillId);
        if (sk == null) {
            return "Error: skill not found: " + skillId;
        }
        Path base = Paths.get(sk.getRootDir());
        Path resolved = base.resolve(path).normalize();
        if (!resolved.startsWith(base)) {
            return "Error: path outside skill dir";
        }
        if (!Files.isRegularFile(resolved)) {
            return "Error: resource not found: " + path + "\nAvailable: " + listResources(skillId);
        }
        try {
            return new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    @Override
    public List<String> listResources(String skillId) {
        Skill sk = load(skillId);
        if (sk == null) {
            return Collections.emptyList();
        }
        List<String> r = new ArrayList<>();
        r.add("SKILL.md");
        r.addAll(sk.getResources());
        return r;
    }
}
