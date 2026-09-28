package tests.java;

import com.weizhi.WeizhiEngine;
import com.weizhi.desktop.DesktopCaps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Desktop integration tests for {@code assets/office/docx.js} (model + render). */
public final class OfficeTest {
    public static void main(String[] args) throws Exception {
        String platform = DesktopCaps.platformObjectName();
        if (platform == null) {
            throw new AssertionError("desktop platform required");
        }
        Path root = Files.createTempDirectory("weizhi-office-");
        Path scriptDir = Path.of(System.getProperty("user.dir"), "assets", "office");
        try (WeizhiEngine engine = new WeizhiEngine()) {
            DesktopCaps.install(engine, root, message -> true);
            if (!Files.isDirectory(scriptDir)) {
                throw new AssertionError("missing script dir " + scriptDir);
            }
            engine.setScriptFolder(scriptDir.toString());
            write(root.resolve("notes/report.md"), "# Hello\n\n- item one\n- item two\n");

            String mdOut = engine.runJs(
                    "import { markdownToDocx } from './docx.js';\n"
                            + "export default markdownToDocx({"
                            + "inputPath:'notes/report.md', outputPath:'out/report.docx', title:'Report'});\n",
                    10000);
            assertContains(mdOut, "\"ok\":true");
            assertContains(mdOut, "\"path\":\"out/report.docx\"");
            assertPkZip(root.resolve("out/report.docx"));
            assertZipEntryContains(root.resolve("out/report.docx"), "word/document.xml", "Hello");

            String modelOut = engine.runJs(
                    "import { Document, renderDocx } from './docx.js';\n"
                            + "var doc = Document.create({ title: 'T' });\n"
                            + "doc.addHeading('Edited', 1);\n"
                            + "doc.setDefaultStyle({ font: 'SimSun', sizePt: 12, lineSpacing: 1.5 });\n"
                            + "doc.addParagraph('Body');\n"
                            + "export default renderDocx(doc, 'out/model.docx');\n",
                    10000);
            assertContains(modelOut, "\"ok\":true");
            assertZipEntryContains(root.resolve("out/model.docx"), "word/document.xml", "Edited");
            assertZipEntryContains(root.resolve("out/model.docx"), "word/document.xml", "SimSun");

            try {
                engine.runJs(
                        "import { markdownToDocx } from './docx.js';\n"
                                + "markdownToDocx({inputPath:'notes/report.md', outputPath:'../outside.docx'});\n",
                        5000);
                throw new AssertionError("expected path escape");
            } catch (RuntimeException e) {
                String msg = e.getMessage();
                if (msg == null || (!msg.contains("path") && !msg.contains("escape") && !msg.contains("invalid"))) {
                    throw e;
                }
            }

            Path artifacts = Path.of(System.getProperty("user.dir"), "build", "office-artifacts");
            Files.createDirectories(artifacts);
            Files.copy(root.resolve("out/report.docx"), artifacts.resolve("report.docx"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Office docx.js OK (" + platform + ")");
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void assertContains(String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) {
            throw new AssertionError("missing " + needle + " in " + haystack);
        }
    }

    private static void assertPkZip(Path zipPath) throws IOException {
        byte[] head = Files.readAllBytes(zipPath);
        if (head.length < 2 || head[0] != 'P' || head[1] != 'K') {
            throw new AssertionError("not a PK zip: " + zipPath);
        }
    }

    private static void assertZipEntryContains(Path zipPath, String entryName, String text) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    String xml = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    if (!xml.contains(text)) {
                        throw new AssertionError("entry " + entryName + " missing " + text);
                    }
                    return;
                }
            }
        }
        throw new AssertionError("missing zip entry " + entryName);
    }
}
