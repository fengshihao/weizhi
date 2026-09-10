package com.weizhi.agent.tool.builtin;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
}
