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
            write(root.resolve("notes/rich.md"),
                    "# Rich\n\n"
                            + "Visit [site](https://example.com) for **bold**.\n\n"
                            + "- [ ] task open\n"
                            + "- [x] task done\n\n"
                            + "| A | B |\n"
                            + "| --- | --- |\n"
                            + "| 1 | 2 |\n\n"
                            + "> quote line\n\n"
                            + "```\ncode();\n```\n");

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

            String rich = engine.runJs(
                    "import { markdownToDocx } from './docx.js';\n"
                            + "export default markdownToDocx({inputPath:'notes/rich.md', outputPath:'out/rich.docx'});\n",
                    12000);
            assertContains(rich, "\"ok\":true");
            assertZipEntryContains(root.resolve("out/rich.docx"), "word/document.xml", "site");
            assertZipEntryContains(root.resolve("out/rich.docx"), "word/document.xml", "w:tbl");

            String round = engine.runJs(
                    "import { readDocx } from './docx.js';\n"
                            + "var doc = readDocx('out/rich.docx');\n"
                            + "doc.addParagraph('After load edit');\n"
                            + "var r = doc.save('out/rich-edited.docx');\n"
                            + "export default { bytes: r.bytes, has: doc.toMarkdown().indexOf('After load') >= 0 };\n",
                    15000);
            assertContains(round, "\"has\":true");
            assertZipEntryContains(root.resolve("out/rich-edited.docx"), "word/document.xml", "After load");

            String grepEdit = engine.runJs(
                    "import { readDocx } from './docx.js';\n"
                            + "var doc = readDocx('out/rich.docx');\n"
                            + "var heads = doc.headings();\n"
                            + "var g = doc.grep('bold');\n"
                            + "doc.replaceAll('bold', 'strong');\n"
                            + "var r = doc.save('out/grep-edited.docx');\n"
                            + "export default {"
                            + "heading: heads[0] && heads[0].text,"
                            + "grepCount: g.matches.length,"
                            + "hasStrong: doc.plainText().indexOf('strong') >= 0,"
                            + "bytes: r.bytes"
                            + "};\n",
                    15000);
            assertContains(grepEdit, "\"heading\":\"Rich\"");
            assertContains(grepEdit, "\"grepCount\":");
            assertContains(grepEdit, "\"hasStrong\":true");
            assertZipEntryContains(root.resolve("out/grep-edited.docx"), "word/document.xml", "strong");

            String styleFlow = engine.runJs(
                    "import { Document, renderDocx, readDocx } from './docx.js';\n"
                            + "var doc = Document.create({ defaultStyle: { font: 'SimSun', sizePt: 12 } });\n"
                            + "doc.addParagraph('Small', { sizePt: 10 });\n"
                            + "doc.addParagraph('Large', { sizePt: 16, bold: true });\n"
                            + "renderDocx(doc, 'out/style.docx');\n"
                            + "var loaded = readDocx('out/style.docx');\n"
                            + "var hits = loaded.grepStyles({ sizePt: 16 });\n"
                            + "loaded.setBlockStyle(hits[0].blockIndex, { font: 'SimHei', sizePt: 18 });\n"
                            + "loaded.save('out/style-edited.docx');\n"
                            + "export default { hitText: hits[0] && hits[0].text, eff: loaded.getBlockStyle(hits[0].blockIndex).effective };\n",
                    15000);
            assertContains(styleFlow, "\"hitText\":\"Large\"");
            assertContains(styleFlow, "\"sizePt\":18");
            assertZipEntryContains(root.resolve("out/style-edited.docx"), "word/document.xml", "SimHei");

            String rawFlow = engine.runJs(
                    "import { unpackDocx, packDocx, validateDocx } from './docx-raw.js';\n"
                            + "import fs from 'fs';\n"
                            + "var u = unpackDocx('out/report.docx', { workDir: 'tmp/raw-report' });\n"
                            + "var p = u.dir + '/word/document.xml';\n"
                            + "fs.writeFileSync(p, fs.readFileSync(p).toString().replace('item one', 'ITEM ONE'));\n"
                            + "var bad = validateDocx({ dir: u.dir });\n"
                            + "var packed = packDocx(u.dir, 'out/raw-edited.docx');\n"
                            + "var v = validateDocx({ path: 'out/raw-edited.docx' });\n"
                            + "fs.writeFileSync(p, fs.readFileSync(p).toString().replace('<w:body>', '<w:body>'));\n"
                            + "fs.writeFileSync(p, '<broken');\n"
                            + "var broken = validateDocx({ dir: u.dir, levels: ['xml'] });\n"
                            + "export default { packedOk: packed.ok, validOk: v.ok, badOk: bad.ok, brokenOk: broken.ok === false };\n",
                    20000);
            assertContains(rawFlow, "\"packedOk\":true");
            assertContains(rawFlow, "\"validOk\":true");
            assertContains(rawFlow, "\"brokenOk\":true");

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
