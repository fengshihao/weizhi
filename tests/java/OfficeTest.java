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
            write(root.resolve("notes/headings.md"),
                    "# 一级标题\n\n正文段落 twelve pt。\n\n## 二级标题\n\n另一段正文。\n");
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
                    "import { markdownToDocx } from 'docx.js';\n"
                            + "export default markdownToDocx({"
                            + "inputPath:'notes/report.md', outputPath:'out/report.docx', title:'Report'});\n",
                    10000);
            assertContains(mdOut, "\"ok\":true");
            assertContains(mdOut, "\"path\":\"out/report.docx\"");
            assertPkZip(root.resolve("out/report.docx"));

            String builderOut = engine.runJs(
                    "import { buildDocx } from './docx-build.js';\n"
                            + "export default buildDocx("
                            + "{ title: 'B', defaultStyle: { font: 'SimSun', sizePt: 11 } },"
                            + "function(b){ b.h1('Built').p('Line').table(['C1','C2'],['v1','v2']); },"
                            + "'out/builder.docx'"
                            + ");\n",
                    10000);
            assertContains(builderOut, "\"ok\":true");
            assertZipEntryContains(root.resolve("out/builder.docx"), "word/document.xml", "Built");
            assertZipEntryContains(root.resolve("out/builder.docx"), "word/document.xml", "w:tbl");
            assertZipEntryContains(root.resolve("out/report.docx"), "word/document.xml", "Hello");

            String headingHierarchy = engine.runJs(
                    "import { markdownToDocx, readDocx } from './docx.js';\n"
                            + "markdownToDocx({ inputPath: 'notes/headings.md', outputPath: 'out/headings.docx',"
                            + " defaultStyle: { sizePt: 12 } });\n"
                            + "var doc = readDocx('out/headings.docx');\n"
                            + "var heads = doc.headings();\n"
                            + "var paras = doc.filter({ type: 'paragraph' });\n"
                            + "export default {"
                            + "h1: doc.getBlockStyle(heads[0].index).effective.sizePt,"
                            + "h2: doc.getBlockStyle(heads[1].index).effective.sizePt,"
                            + "body: doc.getBlockStyle(paras[0].index).effective.sizePt,"
                            + "h1Level: heads[0].level,"
                            + "h2Level: heads[1].level"
                            + "};\n",
                    12000);
            assertContains(headingHierarchy, "\"h1\":22");
            assertContains(headingHierarchy, "\"h2\":16");
            assertContains(headingHierarchy, "\"body\":12");
            assertContains(headingHierarchy, "\"h1Level\":1");
            assertContains(headingHierarchy, "\"h2Level\":2");
            assertZipEntryContains(root.resolve("out/headings.docx"), "word/styles.xml", "Heading1");
            assertZipEntryContains(root.resolve("out/headings.docx"), "word/document.xml", "w:sz w:val=\"44\"");

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
                            + "var good = validateDocx({ dir: u.dir });\n"
                            + "var packed = packDocx(u.dir, 'out/raw-edited.docx');\n"
                            + "var v = validateDocx({ path: 'out/raw-edited.docx' });\n"
                            + "fs.writeFileSync(p, '<broken');\n"
                            + "var broken = validateDocx({ dir: u.dir, levels: ['xml'] });\n"
                            + "export default { packedOk: packed.ok, validOk: v.ok, goodOk: good.ok, brokenOk: broken.ok === false, xmlErrors: broken.errors.length };\n",
                    20000);
            assertContains(rawFlow, "\"packedOk\":true");
            assertContains(rawFlow, "\"validOk\":true");
            assertContains(rawFlow, "\"goodOk\":true");
            assertContains(rawFlow, "\"brokenOk\":true");

            String inspect = engine.runJs(
                    "import { readDocx } from './docx.js';\n"
                            + "var doc = readDocx('out/rich.docx');\n"
                            + "var tv = doc.textView({ includeStyle: true });\n"
                            + "var paras = doc.filter({ type: 'paragraph' });\n"
                            + "var first = doc.replaceInBlock(paras[0].index, 'Visit', 'See', { replaceFirst: true });\n"
                            + "var md = doc.toMarkdown();\n"
                            + "export default {"
                            + "viewLines: tv.length,"
                            + "paraCount: paras.length,"
                            + "replaced: first.replaced,"
                            + "mdHasSee: md.indexOf('See') >= 0"
                            + "};\n",
                    15000);
            assertContains(inspect, "\"replaced\":1");
            assertContains(inspect, "\"mdHasSee\":true");

            String validateOnly = engine.runJs(
                    "import { validateDocx } from './docx-raw.js';\n"
                            + "var files = " + platform + ".files;\n"
                            + "files.mkdir('tmp/empty-pkg');\n"
                            + "files.write('tmp/empty-pkg/[Content_Types].xml',"
                            + " '<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"></Types>');\n"
                            + "export default validateDocx({ dir: 'tmp/empty-pkg', levels: ['package'] });\n",
                    10000);
            assertContains(validateOnly, "\"ok\":false");
            assertContains(validateOnly, "package_missing");

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

            byte[] png = java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
            Files.write(root.resolve("notes/dot.png"), png);

            String deck = engine.runJs(
                    "import { renderPptx } from './pptx.js';\n"
                            + "export default renderPptx({"
                            + "title:'季度复盘', theme:'briefing', slides:["
                            + "{layout:'title', title:'季度复盘', subtitle:'2026 Q3'},"
                            + "{layout:'bullets', title:'结论', items:['收入 +12%','留存持平']},"
                            + "{layout:'twoColumn', title:'对比', left:['线上'], right:['线下']},"
                            + "{layout:'stat', title:'指标', items:[{value:'12%', label:'收入'}]},"
                            + "{layout:'steps', title:'流程', items:['发现','修复','上线']},"
                            + "{layout:'callout', title:'记住', text:'一句话结论'},"
                            + "{layout:'cards', title:'要点', items:[{title:'产品', text:'稳定'}]},"
                            + "{layout:'table', title:'表', rows:[['列A','列B'],['1','2']]},"
                            + "{layout:'shapes', title:'示意', shapes:["
                            + "{preset:'ellipse', col:0, row:1, colSpan:4, rowSpan:3, fill:'accent', text:'A'},"
                            + "{preset:'rightArrow', col:4, row:2, colSpan:4, rowSpan:2, fill:'accent2', text:'到'},"
                            + "{preset:'roundRect', col:8, row:1, colSpan:4, rowSpan:3, fill:'surface', text:'B'}"
                            + "]},"
                            + "{layout:'section', title:'下一章', subtitle:'继续'}"
                            + "]}, 'out/deck.pptx');\n",
                    20000);
            assertContains(deck, "\"ok\":true");
            assertContains(deck, "\"slides\":10");
            assertPkZip(root.resolve("out/deck.pptx"));
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/slides/slide1.xml", "季度复盘");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/slides/slide1.xml", "1F4E79");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/slides/slide1.xml", "prst=\"ellipse\"");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/slides/slide2.xml", "收入 +12%");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/slides/slide2.xml", "prst=\"roundRect\"");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/slides/slide5.xml", "prst=\"chevron\"");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/slides/slide6.xml", "一句话结论");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/slides/slide9.xml", "prst=\"rightArrow\"");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "[Content_Types].xml", "presentationml.slide+xml");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/presentation.xml", "screen16x9");
            assertZipEntryContains(root.resolve("out/deck.pptx"), "ppt/theme/theme1.xml", "a:theme");

            String dark = engine.runJs(
                    "import { renderPptx } from './pptx.js';\n"
                            + "export default renderPptx({ theme:'dark', slides:["
                            + "{ layout:'title', title:'Night', background:'dark' },"
                            + "{ layout:'title', title:'Cover', background:{ image:'notes/dot.png' } },"
                            + "{ layout:'image', title:'图', image:'notes/dot.png', text:'说明' }"
                            + "]}, 'out/dark.pptx');\n",
                    15000);
            assertContains(dark, "\"ok\":true");
            assertZipEntryContains(root.resolve("out/dark.pptx"), "ppt/slides/slide1.xml", "a:gradFill");
            assertZipEntryContains(root.resolve("out/dark.pptx"), "ppt/slides/slide1.xml", "1B2430");
            assertZipEntryContains(root.resolve("out/dark.pptx"), "ppt/slides/slide2.xml", "r:embed");
            assertZipEntryContains(root.resolve("out/dark.pptx"), "ppt/slides/_rels/slide2.xml.rels", "image");
            assertZipEntryContains(root.resolve("out/dark.pptx"), "ppt/slides/slide3.xml", "p:pic");
            assertZipEntryContains(root.resolve("out/dark.pptx"), "ppt/slides/slide3.xml", "说明");
            assertZipEntryContains(root.resolve("out/dark.pptx"), "ppt/media/image1.png", "PNG");

            String built = engine.runJs(
                    "import { buildPptx } from './pptx-build.js';\n"
                            + "export default buildPptx({ title:'B', theme:'briefing' }, function (b) {"
                            + "b.title('Built','Sub').bullets('要点',['一','二']).shapes('图', ["
                            + "{ preset:'rect', col:0, row:0, colSpan:12, rowSpan:2, fill:'accent', text:'条' }"
                            + "]); }, 'out/built.pptx');\n",
                    15000);
            assertContains(built, "\"ok\":true");
            assertZipEntryContains(root.resolve("out/built.pptx"), "ppt/slides/slide1.xml", "Built");
            assertZipEntryContains(root.resolve("out/built.pptx"), "ppt/slides/slide3.xml", "prst=\"rect\"");

            String rejected = engine.runJs(
                    "import { renderPptx } from './pptx.js';\n"
                            + "function grab(fn){ try { fn(); return 'ok'; } catch (e) { return String(e && e.message ? e.message : e); } }\n"
                            + "export default {\n"
                            + "theme: grab(function(){ renderPptx({ theme:'neon', slides:[{layout:'title', title:'x'}] }, 'out/bad-theme.pptx'); }),\n"
                            + "layout: grab(function(){ renderPptx({ slides:[{layout:'nope', title:'x'}] }, 'out/bad-layout.pptx'); }),\n"
                            + "shapes: grab(function(){ renderPptx({ slides:[{layout:'shapes', shapes:["
                            + "{col:0,row:0,colSpan:1,rowSpan:1},{col:1,row:0,colSpan:1,rowSpan:1},"
                            + "{col:2,row:0,colSpan:1,rowSpan:1},{col:3,row:0,colSpan:1,rowSpan:1},"
                            + "{col:4,row:0,colSpan:1,rowSpan:1},{col:5,row:0,colSpan:1,rowSpan:1},"
                            + "{col:6,row:0,colSpan:1,rowSpan:1}] }] }, 'out/bad-shapes.pptx'); }),\n"
                            + "grid: grab(function(){ renderPptx({ slides:[{layout:'shapes', shapes:[{col:11,row:0,colSpan:2,rowSpan:1}]}] }, 'out/bad-grid.pptx'); }),\n"
                            + "empty: grab(function(){ renderPptx({}, 'out/bad-empty.pptx'); })\n"
                            + "};\n",
                    15000);
            assertContains(rejected, "bad argument");
            assertContains(rejected, "unknown theme");
            assertContains(rejected, "unknown layout");
            assertContains(rejected, "too many shapes");
            assertContains(rejected, "shape out of grid");
            assertContains(rejected, "slides required");

            try {
                engine.runJs(
                        "import { renderPptx } from './pptx.js';\n"
                                + "renderPptx({ slides:[{layout:'title', title:'x'}] }, '../outside.pptx');\n",
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
            Files.copy(root.resolve("out/deck.pptx"), artifacts.resolve("deck.pptx"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("Office docx.js / pptx.js OK (" + platform + ")");
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
