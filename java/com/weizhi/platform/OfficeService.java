package com.weizhi.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal OOXML writer (docx / xlsx / pptx) under {@link LocalWorkspace}.
 * Uses directory + {@link ZipTools#create} — no third-party Office libraries.
 */
public final class OfficeService {
    private final LocalWorkspace workspace;
    private final ZipTools zip;

    public OfficeService(LocalWorkspace workspace) {
        this.workspace = workspace;
        this.zip = new ZipTools(workspace);
    }

    public String docxFromMarkdown(Map<String, Object> args) {
        try {
            String inputPath = MiniJson.str(args, "inputPath");
            String outputPath = MiniJson.str(args, "outputPath");
            if (outputPath.isEmpty()) {
                return MiniJson.error("bad argument: office.docx.fromMarkdown: outputPath required");
            }
            String title = MiniJson.str(args, "title");
            String md;
            if (!inputPath.isEmpty()) {
                md = workspace.read(inputPath);
            } else {
                md = MiniJson.str(args, "markdown");
            }
            if (md.isEmpty() && title.isEmpty()) {
                return MiniJson.error("bad argument: office.docx.fromMarkdown: inputPath or markdown required");
            }
            List<OfficeMarkdown.Block> blocks = OfficeMarkdown.parseDocument(md);
            if (!title.isEmpty()) {
                blocks.add(0, new OfficeMarkdown.Block(OfficeMarkdown.Block.Kind.HEADING, 1, title));
            }
            String buildDir = "tmp/office-docx-" + System.nanoTime();
            writeDocxTree(buildDir, blocks);
            String zipMsg = zip.create(buildDir, outputPath);
            if (zipMsg.startsWith("Error:")) {
                deleteQuiet(buildDir);
                return MiniJson.error(zipMsg.substring("Error:".length()).trim());
            }
            long bytes = Files.size(workspace.resolve(outputPath));
            deleteQuiet(buildDir);
            workspace.note("office.docx.fromMarkdown", outputPath);
            return ok(outputPath, bytes);
        } catch (IllegalArgumentException e) {
            return MiniJson.error(e.getMessage() == null ? "bad argument" : e.getMessage());
        } catch (IOException e) {
            return MiniJson.error("office.docx.fromMarkdown failed: " + e.getMessage());
        }
    }

    public String xlsxFromRows(Map<String, Object> args) {
        try {
            String outputPath = MiniJson.str(args, "outputPath");
            if (outputPath.isEmpty()) {
                return MiniJson.error("bad argument: office.xlsx.fromRows: outputPath required");
            }
            String sheetName = MiniJson.str(args, "sheetName");
            if (sheetName.isEmpty()) {
                sheetName = "Sheet1";
            }
            List<List<Object>> rows = readRows(args);
            if (rows.isEmpty()) {
                return MiniJson.error("bad argument: office.xlsx.fromRows: rows or inputPath required");
            }
            String buildDir = "tmp/office-xlsx-" + System.nanoTime();
            writeXlsxTree(buildDir, sheetName, rows);
            String zipMsg = zip.create(buildDir, outputPath);
            if (zipMsg.startsWith("Error:")) {
                deleteQuiet(buildDir);
                return MiniJson.error(zipMsg.substring("Error:".length()).trim());
            }
            long bytes = Files.size(workspace.resolve(outputPath));
            deleteQuiet(buildDir);
            workspace.note("office.xlsx.fromRows", outputPath);
            return ok(outputPath, bytes);
        } catch (IllegalArgumentException e) {
            return MiniJson.error(e.getMessage() == null ? "bad argument" : e.getMessage());
        } catch (IOException e) {
            return MiniJson.error("office.xlsx.fromRows failed: " + e.getMessage());
        }
    }

    public String pptxFromMarkdown(Map<String, Object> args) {
        try {
            String inputPath = MiniJson.str(args, "inputPath");
            String outputPath = MiniJson.str(args, "outputPath");
            if (outputPath.isEmpty()) {
                return MiniJson.error("bad argument: office.pptx.fromMarkdown: outputPath required");
            }
            String md = inputPath.isEmpty() ? MiniJson.str(args, "markdown") : workspace.read(inputPath);
            List<OfficeMarkdown.Slide> slides = OfficeMarkdown.parseSlides(md);
            String buildDir = "tmp/office-pptx-" + System.nanoTime();
            writePptxTree(buildDir, slides);
            String zipMsg = zip.create(buildDir, outputPath);
            if (zipMsg.startsWith("Error:")) {
                deleteQuiet(buildDir);
                return MiniJson.error(zipMsg.substring("Error:".length()).trim());
            }
            long bytes = Files.size(workspace.resolve(outputPath));
            deleteQuiet(buildDir);
            workspace.note("office.pptx.fromMarkdown", outputPath);
            return ok(outputPath, bytes);
        } catch (IllegalArgumentException e) {
            return MiniJson.error(e.getMessage() == null ? "bad argument" : e.getMessage());
        } catch (IOException e) {
            return MiniJson.error("office.pptx.fromMarkdown failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<List<Object>> readRows(Map<String, Object> args) throws IOException {
        Object rowsVal = args.get("rows");
        if (rowsVal instanceof List) {
            List<List<Object>> out = new ArrayList<>();
            for (Object rowObj : (List<Object>) rowsVal) {
                if (!(rowObj instanceof List)) {
                    throw new IllegalArgumentException("bad argument: office.xlsx.fromRows: rows must be array of arrays");
                }
                out.add(new ArrayList<>((List<Object>) rowObj));
            }
            return out;
        }
        String inputPath = MiniJson.str(args, "inputPath");
        if (inputPath.isEmpty()) {
            return List.of();
        }
        String csv = workspace.read(inputPath);
        List<List<Object>> rows = new ArrayList<>();
        for (String line : csv.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(",", -1);
            List<Object> row = new ArrayList<>();
            for (String cell : cells) {
                row.add(parseCell(cell.strip()));
            }
            rows.add(row);
        }
        return rows;
    }

    private static Object parseCell(String cell) {
        if (cell.isEmpty()) {
            return "";
        }
        try {
            if (cell.indexOf('.') >= 0) {
                return Double.parseDouble(cell);
            }
            return Long.parseLong(cell);
        } catch (NumberFormatException e) {
            return cell;
        }
    }

    private void writeDocxTree(String buildDir, List<OfficeMarkdown.Block> blocks) throws IOException {
        writeUtf8(buildDir, "[Content_Types].xml", docxContentTypes());
        writeUtf8(buildDir, "_rels/.rels", docxRootRels());
        writeUtf8(buildDir, "word/document.xml", docxDocument(blocks));
        writeUtf8(buildDir, "word/_rels/document.xml.rels", docxDocumentRels());
        writeUtf8(buildDir, "docProps/core.xml", coreProps("Weizhi docx"));
    }

    private void writeXlsxTree(String buildDir, String sheetName, List<List<Object>> rows) throws IOException {
        writeUtf8(buildDir, "[Content_Types].xml", xlsxContentTypes());
        writeUtf8(buildDir, "_rels/.rels", xlsxRootRels());
        writeUtf8(buildDir, "xl/workbook.xml", xlsxWorkbook(sheetName));
        writeUtf8(buildDir, "xl/_rels/workbook.xml.rels", xlsxWorkbookRels());
        writeUtf8(buildDir, "xl/worksheets/sheet1.xml", xlsxSheet(rows));
        writeUtf8(buildDir, "xl/styles.xml", xlsxStyles());
        writeUtf8(buildDir, "docProps/core.xml", coreProps("Weizhi xlsx"));
    }

    private void writePptxTree(String buildDir, List<OfficeMarkdown.Slide> slides) throws IOException {
        writeUtf8(buildDir, "[Content_Types].xml", pptxContentTypes(slides.size()));
        writeUtf8(buildDir, "_rels/.rels", pptxRootRels());
        writeUtf8(buildDir, "ppt/presentation.xml", pptxPresentation(slides.size()));
        writeUtf8(buildDir, "ppt/_rels/presentation.xml.rels", pptxPresentationRels(slides.size()));
        writeUtf8(buildDir, "docProps/core.xml", coreProps("Weizhi pptx"));
        for (int i = 0; i < slides.size(); i++) {
            int n = i + 1;
            writeUtf8(buildDir, "ppt/slides/slide" + n + ".xml", pptxSlide(slides.get(i)));
            writeUtf8(buildDir, "ppt/slides/_rels/slide" + n + ".xml.rels", pptxSlideRels());
        }
    }

    private void writeUtf8(String buildDir, String relPath, String xml) throws IOException {
        Path file = workspace.resolve(buildDir + "/" + relPath);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, xml, StandardCharsets.UTF_8);
    }

    private void deleteQuiet(String buildDir) {
        try {
            Path root = workspace.resolve(buildDir);
            if (!Files.exists(root)) {
                return;
            }
            try (var walk = Files.walk(root)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    private static String ok(String path, long bytes) {
        return "{\"ok\":true,\"path\":" + MiniJson.quote(path) + ",\"bytes\":" + bytes + "}";
    }

    private static String coreProps(String title) {
        return OfficeXml.xmlDecl()
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:dcterms=\"http://purl.org/dc/terms/\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
                + "<dc:title>" + OfficeXml.escape(title) + "</dc:title>"
                + "<dc:creator>Weizhi</dc:creator>"
                + "</cp:coreProperties>";
    }

    private static String docxContentTypes() {
        return OfficeXml.xmlDecl()
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\""
                + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\""
                + " ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "</Types>";
    }

    private static String docxRootRels() {
        return OfficeXml.xmlDecl()
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\""
                + " Target=\"docProps/core.xml\"/>"
                + "</Relationships>";
    }

    private static String docxDocumentRels() {
        return OfficeXml.xmlDecl()
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>";
    }

    private static String docxDocument(List<OfficeMarkdown.Block> blocks) {
        StringBuilder body = new StringBuilder();
        body.append(OfficeXml.xmlDecl());
        body.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">");
        body.append("<w:body>");
        for (OfficeMarkdown.Block block : blocks) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">");
            switch (block.kind) {
                case HEADING:
                    body.append(OfficeXml.escape(block.text));
                    break;
                case BULLET:
                    body.append("• ").append(OfficeXml.escape(block.text));
                    break;
                default:
                    body.append(OfficeXml.escape(block.text));
            }
            body.append("</w:t></w:r></w:p>");
        }
        body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>");
        body.append("</w:body></w:document>");
        return body.toString();
    }

    private static String xlsxContentTypes() {
        return OfficeXml.xmlDecl()
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\""
                + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\""
                + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\""
                + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\""
                + " ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "</Types>";
    }

    private static String xlsxRootRels() {
        return OfficeXml.xmlDecl()
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"xl/workbook.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\""
                + " Target=\"docProps/core.xml\"/>"
                + "</Relationships>";
    }

    private static String xlsxWorkbook(String sheetName) {
        return OfficeXml.xmlDecl()
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"" + OfficeXml.escape(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>";
    }

    private static String xlsxWorkbookRels() {
        return OfficeXml.xmlDecl()
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
                + " Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\""
                + " Target=\"styles.xml\"/>"
                + "</Relationships>";
    }

    private static String xlsxStyles() {
        return OfficeXml.xmlDecl()
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>"
                + "<borders count=\"1\"><border/></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>"
                + "</styleSheet>";
    }

    private static String xlsxSheet(List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(OfficeXml.xmlDecl());
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sb.append("<sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            int rowNum = r + 1;
            sb.append("<row r=\"").append(rowNum).append("\">");
            List<Object> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                String col = columnName(c);
                String ref = col + rowNum;
                Object cell = row.get(c);
                sb.append("<c r=\"").append(ref).append("\"");
                if (cell instanceof Number) {
                    sb.append("><v>").append(cell).append("</v></c>");
                } else {
                    sb.append(" t=\"inlineStr\"><is><t>").append(OfficeXml.escape(String.valueOf(cell)))
                            .append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    private static String columnName(int index) {
        int n = index;
        StringBuilder sb = new StringBuilder();
        do {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }

    private static String pptxContentTypes(int slideCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(OfficeXml.xmlDecl());
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        sb.append("<Override PartName=\"/ppt/presentation.xml\""
                + " ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>");
        for (int i = 1; i <= slideCount; i++) {
            sb.append("<Override PartName=\"/ppt/slides/slide").append(i).append(".xml\""
                    + " ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
        }
        sb.append("<Override PartName=\"/docProps/core.xml\""
                + " ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>");
        sb.append("</Types>");
        return sb.toString();
    }

    private static String pptxRootRels() {
        return OfficeXml.xmlDecl()
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"ppt/presentation.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\""
                + " Target=\"docProps/core.xml\"/>"
                + "</Relationships>";
    }

    private static String pptxPresentation(int slideCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(OfficeXml.xmlDecl());
        sb.append("<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                + " xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">");
        sb.append("<p:sldIdLst>");
        for (int i = 0; i < slideCount; i++) {
            sb.append("<p:sldId id=\"").append(256 + i).append("\" r:id=\"rId").append(i + 2).append("\"/>");
        }
        sb.append("</p:sldIdLst>");
        sb.append("<p:sldSz cx=\"9144000\" cy=\"6858000\" type=\"screen4x3\"/>");
        sb.append("</p:presentation>");
        return sb.toString();
    }

    private static String pptxPresentationRels(int slideCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(OfficeXml.xmlDecl());
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < slideCount; i++) {
            int n = i + 1;
            sb.append("<Relationship Id=\"rId").append(i + 2)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\""
                            + " Target=\"slides/slide").append(n).append(".xml\"/>");
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    private static String pptxSlideRels() {
        return OfficeXml.xmlDecl()
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>";
    }

    private static String pptxSlide(OfficeMarkdown.Slide slide) {
        StringBuilder bullets = new StringBuilder();
        for (String b : slide.bullets) {
            bullets.append("• ").append(b).append("\n");
        }
        String body = bullets.length() == 0 ? "" : bullets.toString().stripTrailing();
        return OfficeXml.xmlDecl()
                + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                + " xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                + "<p:cSld><p:spTree>"
                + "<p:sp><p:nvSpPr><p:cNvPr id=\"1\" name=\"Title\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>"
                + OfficeXml.escape(slide.title)
                + "</a:t></a:r></a:p></p:txBody></p:sp>"
                + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Body\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t xml:space=\"preserve\">"
                + OfficeXml.escape(body)
                + "</a:t></a:r></a:p></p:txBody></p:sp>"
                + "</p:spTree></p:cSld></p:sld>";
    }
}
