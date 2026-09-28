package tests.java;

import com.weizhi.WeizhiEngine;
import com.weizhi.desktop.DesktopCaps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Desktop integration tests for {@code host.office.*} (docx / xlsx / pptx + sandbox).
 */
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
            if (Files.isDirectory(scriptDir)) {
                engine.setScriptFolder(scriptDir.toString());
            }
            write(root.resolve("notes/report.md"), "# Hello\n\n- item one\n- item two\n");

            String docx = engine.runJs(
                    "host.office.docx.fromMarkdown({inputPath:'notes/report.md', outputPath:'out/report.docx', title:'Report'})",
                    8000);
            assertContains(docx, "\"ok\":true");
            assertContains(docx, "\"path\":\"out/report.docx\"");
            assertPkZip(root.resolve("out/report.docx"));
            assertZipEntryContains(root.resolve("out/report.docx"), "word/document.xml", "Hello");
            assertZipEntryContains(root.resolve("out/report.docx"), "word/document.xml", "item one");

            String bad = engine.runJs(
                    "host.office.docx.fromMarkdown({inputPath:'notes/report.md', outputPath:'../outside.docx'})",
                    5000);
            assertContains(bad, "\"ok\":false");
            assertContains(bad, "path escape");

            String xlsx = engine.runJs(
                    "host.office.xlsx.fromRows({outputPath:'out/data.xlsx', sheetName:'Data', rows:[['A','B'],[1,2]]})",
                    8000);
            assertContains(xlsx, "\"ok\":true");
            assertPkZip(root.resolve("out/data.xlsx"));
            assertZipEntryContains(root.resolve("out/data.xlsx"), "xl/worksheets/sheet1.xml", "A");

            write(root.resolve("slides/deck.md"), "# Intro\n\n---\n\n# Next\n\n- point\n");
            String pptx = engine.runJs(
                    "host.office.pptx.fromMarkdown({inputPath:'slides/deck.md', outputPath:'out/deck.pptx'})",
                    10000);
            assertContains(pptx, "\"ok\":true");
            assertPkZip(root.resolve("out/deck.pptx"));
            assertZipEntryExists(root.resolve("out/deck.pptx"), "ppt/presentation.xml");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/slides/slide1.xml", "Intro");

            if (Files.isDirectory(scriptDir)) {
                String edited = engine.runJs(
                        "import WeizhiDocx from './weizhi-docx.js';"
                                + "var doc = WeizhiDocx.open('out/report.docx');"
                                + "doc.setTitle('Edited Title');"
                                + "doc.setBodyStyle({font:'SimSun', sizePt:12, lineSpacing:1.5});"
                                + "doc.save('out/report-edited.docx');"
                                + "export default doc.getTitle();",
                        10000);
                if (!"\"Edited Title\"".equals(edited)) {
                    throw new AssertionError("want Edited Title got " + edited);
                }
                assertZipEntryContains(root.resolve("out/report-edited.docx"), "word/document.xml", "Edited Title");
                assertZipEntryContains(root.resolve("out/report-edited.docx"), "word/document.xml", "SimSun");
                assertZipEntryContains(root.resolve("out/report-edited.docx"), "word/document.xml", "w:val=\"24\"");
            }

            Path artifacts = Path.of(System.getProperty("user.dir"), "build", "office-artifacts");
            Files.createDirectories(artifacts);
            Files.copy(root.resolve("out/report.docx"), artifacts.resolve("report.docx"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Office host.office OK (" + platform + ")");
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

    private static void assertZipEntryExists(Path zipPath, String entryName) throws IOException {
        if (!zipHasEntry(zipPath, entryName, null)) {
            throw new AssertionError("missing zip entry " + entryName);
        }
    }

    private static void assertZipEntryContains(Path zipPath, String entryName, String text) throws IOException {
        if (!zipHasEntry(zipPath, entryName, text)) {
            throw new AssertionError("entry " + entryName + " missing text " + text);
        }
    }

    private static boolean zipHasEntry(Path zipPath, String entryName, String mustContain) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    if (mustContain == null) {
                        return true;
                    }
                    byte[] buf = zis.readAllBytes();
                    String xml = new String(buf, StandardCharsets.UTF_8);
                    return xml.contains(mustContain);
                }
            }
        }
        return false;
    }
}
