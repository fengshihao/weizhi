package com.weizhi.agent.sandbox;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
