package com.weizhi.agent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public final class MarkdownSkillParser {

    private MarkdownSkillParser() {
    }

    public static Skill parse(Path skillDir) throws IOException {
        Path md = skillDir.resolve("SKILL.md");
        byte[] bytes = Files.readAllBytes(md);
        List<String> resources = scanResources(skillDir);
        Path fileName = skillDir.getFileName();
        String id = fileName != null ? fileName.toString() : skillDir.toString();
        return parse(id, skillDir.toString(), bytes, resources);
    }

    public static Skill parse(String id, String rootDir, byte[] skillMdBytes, List<String> resources) {
        String content = new String(skillMdBytes, StandardCharsets.UTF_8);
        String frontmatter = "";
        String body = content;
        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end > 0) {
                frontmatter = content.substring(3, end).trim();
                int bodyStart = end + 4;
                body = bodyStart < content.length() ? content.substring(bodyStart).trim() : "";
            }
        }
        String description = field(frontmatter, "description");
        if (description == null || description.isEmpty()) {
            description = bodySummary(body);
        }
        return new Skill(id, description, rootDir, body,
                resources == null ? Collections.emptyList() : resources);
    }

    private static String bodySummary(String body) {
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("---")
                    || t.matches("^[A-Za-z_][A-Za-z0-9_]*\\s*:.*")) {
                continue;
            }
            if (t.length() > 80) {
                t = t.substring(0, 80) + "…";
            }
            return t;
        }
        return "";
    }

    private static String field(String yaml, String key) {
        String prefix = key + ":";
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                String v = trimmed.substring(prefix.length()).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    private static List<String> scanResources(Path dir) {
        List<String> res = new ArrayList<>();
        collect(dir.resolve("references"), dir, res);
        collect(dir.resolve("scripts"), dir, res);
        return res;
    }

    private static void collect(Path sub, Path base, List<String> res) {
        if (!Files.isDirectory(sub)) {
            return;
        }
        try (Stream<Path> s = Files.walk(sub)) {
            s.filter(Files::isRegularFile).forEach(p ->
                    res.add(base.relativize(p).toString().replace('\\', '/')));
        } catch (IOException ignored) {
        }
    }
}
