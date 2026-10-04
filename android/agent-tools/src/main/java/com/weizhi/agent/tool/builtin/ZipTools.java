package com.weizhi.agent.tool.builtin;

import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;
import com.weizhi.platform.LocalWorkspace;

import java.nio.file.Path;

/**
 * 薄包装 {@link com.weizhi.platform.ZipTools}，路径经 {@link WorkspaceSandbox} 校验。
 */
public class ZipTools {

    private final WorkspaceSandbox sandbox;

    public ZipTools(WorkspaceSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Tool(name = "zip_extract",
            description = "解压 zip 到工作区目录（默认 tmp/<zip名>/）。",
            readOnly = false, concurrencySafe = false)
    public String zipExtract(
            @ToolParam(name = "file", description = "zip 路径（工作区相对或只读区绝对）") String file,
            @ToolParam(name = "dest", required = false, description = "目标目录（工作区内相对）") String dest) {
        try {
            Path zipPath = sandbox.resolveRead(file);
            LocalWorkspace ws = new LocalWorkspace(sandbox.getBaseDir());
            String wsRel;
            if (zipPath.startsWith(sandbox.getBaseDir())) {
                wsRel = sandbox.relativize(zipPath);
            } else {
                Path zipName = zipPath.getFileName();
                if (zipName == null) {
                    return "Error: bad zip path";
                }
                Path staging = sandbox.getBaseDir().resolve("tmp/_zip_import")
                        .resolve(zipName).normalize();
                if (!staging.startsWith(sandbox.getBaseDir())) {
                    return "Error: path escape";
                }
                Path stagingParent = staging.getParent();
                if (stagingParent != null) {
                    java.nio.file.Files.createDirectories(stagingParent);
                }
                java.nio.file.Files.copy(zipPath, staging,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                wsRel = sandbox.relativize(staging);
            }
            return new com.weizhi.platform.ZipTools(ws).extract(wsRel, dest);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "zip_create",
            description = "将工作区目录打包为 zip。",
            readOnly = false, concurrencySafe = false)
    public String zipCreate(
            @ToolParam(name = "sourceDir", description = "源目录（工作区内相对）") String sourceDir,
            @ToolParam(name = "file", description = "输出 zip（工作区内相对）") String file) {
        try {
            sandbox.resolveWrite(sourceDir);
            sandbox.resolveWrite(file);
            LocalWorkspace ws = new LocalWorkspace(sandbox.getBaseDir());
            return new com.weizhi.platform.ZipTools(ws).create(sourceDir, file);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
