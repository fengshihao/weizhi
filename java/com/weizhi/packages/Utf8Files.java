package com.weizhi.packages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code Files.readString} / {@code writeString} are not in Android's android.jar. */
final class Utf8Files {

    private Utf8Files() {
    }

    static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    static void write(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
