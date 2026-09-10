package com.weizhi.agent.skill;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置只读 Skill：{@code assets/<assetRoot>/<skillId>/SKILL.md}。
 * 动态层 {@link FileSystemSkillRepository} 可覆盖同名 skill。
 */
public final class AssetSkillRepository implements SkillRepository {

    private final AssetManager am;
    private final String assetRoot;

    public AssetSkillRepository(Context context, String assetRoot) {
        this.am = context.getApplicationContext().getAssets();
        this.assetRoot = assetRoot;
    }

    @Override
    public List<Skill> list() {
        List<Skill> all = new ArrayList<>();
        String[] dirs;
        try {
            dirs = am.list(assetRoot);
        } catch (IOException e) {
            return all;
        }
        if (dirs == null) {
            return all;
        }
        for (String id : dirs) {
            byte[] md = readBytes(id, "SKILL.md");
            if (md == null) {
                continue;
            }
            try {
                List<String> resources = listResourcesRecursive(id, "references");
                Skill sk = MarkdownSkillParser.parse(id, assetRoot + "/" + id, md, resources);
                all.add(new Skill(sk.getId(), sk.getDescription(),
                        sk.getRootDir(), "", sk.getResources()));
            } catch (Exception ignored) {
            }
        }
        return all;
    }

    @Override
    public Skill load(String skillId) {
        byte[] md = readBytes(skillId, "SKILL.md");
        if (md == null) {
            return null;
        }
        try {
            List<String> resources = listResourcesRecursive(skillId, "references");
            return MarkdownSkillParser.parse(skillId, assetRoot + "/" + skillId, md, resources);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String readResource(String skillId, String path) {
        if (path == null || path.equals(".") || path.equals("./") || path.startsWith("/")) {
            return "Error: path must be a relative file (e.g. 'SKILL.md'), got: " + path;
        }
        byte[] data = readBytes(skillId, path);
        if (data == null) {
            return "Error: resource not found: " + path + "\nAvailable: " + listResources(skillId);
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public List<String> listResources(String skillId) {
        List<String> r = new ArrayList<>();
        r.add("SKILL.md");
        r.addAll(listResourcesRecursive(skillId, "references"));
        return r;
    }

    private byte[] readBytes(String skillId, String path) {
        String full = assetRoot + "/" + skillId + "/" + path;
        try (InputStream is = am.open(full)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private List<String> listResourcesRecursive(String skillId, String dir) {
        List<String> out = new ArrayList<>();
        collectAssets(assetRoot + "/" + skillId + "/" + dir, dir, out);
        return out;
    }

    private void collectAssets(String assetPath, String relPrefix, List<String> out) {
        String[] names;
        try {
            names = am.list(assetPath);
        } catch (IOException e) {
            return;
        }
        if (names == null) {
            return;
        }
        for (String name : names) {
            String childAsset = assetPath + "/" + name;
            String childRel = relPrefix + "/" + name;
            if (isFile(childAsset)) {
                out.add(childRel);
            } else {
                collectAssets(childAsset, childRel, out);
            }
        }
    }

    private boolean isFile(String assetPath) {
        try (InputStream is = am.open(assetPath)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
