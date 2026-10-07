package com.weizhi.agent.tool.builtin;

import com.weizhi.agent.sandbox.WorkspaceSandbox;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BashToolTest {

    @Test
    public void tokenizeQuotes() {
        assertArrayEquals(new String[]{"ls", "-la", "my file.txt"},
                BashTool.tokenize("ls -la \"my file.txt\""));
    }

    @Test
    public void tokenizeSingleQuotes() {
        assertArrayEquals(new String[]{"echo", "hello world"},
                BashTool.tokenize("echo 'hello world'"));
    }

    @Test
    public void allowedCommandsCoverReadWritePartition() {
        for (String w : BashTool.WRITE_COMMANDS) {
            assertEquals(true, BashTool.ALLOWED_COMMANDS.contains(w));
        }
        for (String r : BashTool.READ_COMMANDS) {
            assertEquals(true, BashTool.ALLOWED_COMMANDS.contains(r));
        }
    }

    @Test
    public void largeOutputDoesNotDeadlockOnPipe() throws Exception {
        Path ws = Files.createTempDirectory("weizhi-bash");
        Path big = ws.resolve("big.txt");
        StringBuilder content = new StringBuilder(80_000);
        for (int i = 0; i < 8000; i++) {
            content.append("0123456789");
        }
        Files.write(big, content.toString().getBytes(StandardCharsets.UTF_8));

        BashTool tool = new BashTool(new WorkspaceSandbox(ws));
        String out = tool.bash("cat big.txt");
        assertFalse(out.startsWith("Error:"));
        assertTrue(out.contains("<truncated: output exceeded " + 50_000 + " bytes>"));
        assertTrue(out.length() > 50_000);
    }
}
