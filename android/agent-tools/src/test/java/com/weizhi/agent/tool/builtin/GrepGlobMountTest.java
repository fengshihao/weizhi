package com.weizhi.agent.tool.builtin;

import com.weizhi.agent.sandbox.ReadMount;
import com.weizhi.agent.sandbox.WorkspaceSandbox;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class GrepGlobMountTest {

    @Test
    public void grepAndGlobUseLogicalPathsUnderReadMount() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-ws");
        Path docsSystem = Files.createTempDirectory("weizhi-docs-system");
        Path doc = docsSystem.resolve("guide.md");
        Files.write(doc, "match-me keyword\n".getBytes(StandardCharsets.UTF_8));

        WorkspaceSandbox sandbox = new WorkspaceSandbox(ws, List.of(
                new ReadMount("docs/system", docsSystem)));

        GrepTool grep = new GrepTool(sandbox);
        String grepOut = grep.grep("keyword", "docs/system", null, "content");
        assertTrue(grepOut.contains("docs/system/guide.md:1:match-me keyword"));

        GlobTool glob = new GlobTool(sandbox);
        String globOut = glob.glob("**/*.md", "docs/system", null);
        assertTrue(globOut.contains("docs/system/guide.md"));
    }
}
