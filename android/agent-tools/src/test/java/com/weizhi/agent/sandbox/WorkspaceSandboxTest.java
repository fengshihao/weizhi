package com.weizhi.agent.sandbox;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WorkspaceSandboxTest {

    @Test
    public void resolveWriteBlocksEscape() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-ws");
        WorkspaceSandbox box = new WorkspaceSandbox(ws);
        Path ok = box.resolveWrite("a/b.txt");
        assertTrue(ok.startsWith(ws));
        assertThrows(SecurityException.class, () -> box.resolveWrite("../etc/passwd"));
    }

    @Test
    public void extraReadRootAllowsReadOnly() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-ws");
        Path ro = Files.createTempDirectory("weizhi-ro");
        Path secret = ro.resolve("note.txt");
        Files.write(secret, "ro".getBytes(StandardCharsets.UTF_8));
        WorkspaceSandbox box = new WorkspaceSandbox(ws, ro);
        assertEquals(secret, box.resolveRead(ro.resolve("note.txt").toString()));
        assertThrows(SecurityException.class, () -> box.resolveWrite(ro.resolve("note.txt").toString()));
    }

    @Test
    public void readMountResolvesDocsSystem() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-ws");
        Path agentRoot = Files.createTempDirectory("weizhi-agent");
        Path docsSystem = agentRoot.resolve("docs/system");
        Files.createDirectories(docsSystem);
        Path doc = docsSystem.resolve("office-docx.md");
        Files.write(doc, "hello".getBytes(StandardCharsets.UTF_8));

        WorkspaceSandbox box = new WorkspaceSandbox(ws, List.of(
                new ReadMount("docs/system", docsSystem),
                new ReadMount("docs/capabilities", agentRoot.resolve("docs/capabilities"))));

        assertEquals(doc, box.resolveRead("docs/system/office-docx.md"));
        assertEquals("docs/system/office-docx.md", box.displayPath(doc));
    }

    @Test
    public void readMountRejectsSharedAndUnmountedDocs() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-ws");
        Path agentRoot = Files.createTempDirectory("weizhi-agent");
        Path docsSystem = agentRoot.resolve("docs/system");
        Files.createDirectories(docsSystem);

        WorkspaceSandbox box = new WorkspaceSandbox(ws, List.of(
                new ReadMount("docs/system", docsSystem)));

        assertThrows(SecurityException.class, () -> box.resolveRead("shared/catalog/x"));
        assertThrows(SecurityException.class, () -> box.resolveRead("docs/other/x"));
    }

    @Test
    public void workspaceRelativePathsUnchangedWithoutMounts() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-ws");
        Path note = ws.resolve("notes/foo.md");
        Files.createDirectories(note.getParent());
        Files.write(note, "x".getBytes(StandardCharsets.UTF_8));

        WorkspaceSandbox box = new WorkspaceSandbox(ws);
        assertEquals(note, box.resolveRead("notes/foo.md"));
        assertEquals("notes/foo.md", box.displayPath(note));
    }

    @Test
    public void readMountWriteRejected() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-ws");
        Path docsSystem = Files.createTempDirectory("weizhi-docs-system");
        WorkspaceSandbox box = new WorkspaceSandbox(ws, List.of(new ReadMount("docs/system", docsSystem)));
        assertThrows(SecurityException.class, () -> box.resolveWrite("docs/system/x.md"));
    }
}
