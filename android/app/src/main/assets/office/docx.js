/**
 * Docx: in-memory document model + OOXML renderer (QuickJS ES module).
 *
 * import { Document, renderDocx, markdownToDocx } from "./docx.js";
 */
import fs from "fs";
import zip from "zip";

var W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

function xmlEscape(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function ptToHalfPoints(pt) {
  return Math.round(Number(pt) * 2);
}

function lineSpacingToW(lineSpacing) {
  var mult = Number(lineSpacing);
  if (!(mult > 0)) {
    mult = 1.15;
  }
  return Math.round(240 * mult);
}

function platformFiles() {
  if (typeof linux !== "undefined" && linux.platform === "linux" && linux.files) {
    return linux.files;
  }
  if (typeof mac !== "undefined" && mac.platform === "mac" && mac.files) {
    return mac.files;
  }
  if (typeof android !== "undefined" && android.platform === "android" && android.files) {
    return android.files;
  }
  return null;
}

function packDir(sourceDir, outDocx) {
  var files = platformFiles();
  if (files && files.zipCreate) {
    files.zipCreate(sourceDir, outDocx);
    return;
  }
  zip.createSync(sourceDir, outDocx);
}

function mkdirp(relDir) {
  var files = platformFiles();
  if (!files || !files.mkdir) {
    throw new Error("renderDocx: platform files.mkdir required (install caps)");
  }
  var parts = relDir.split("/");
  var acc = "";
  for (var i = 0; i < parts.length; i++) {
    if (!parts[i]) {
      continue;
    }
    acc = acc ? acc + "/" + parts[i] : parts[i];
    files.mkdir(acc);
  }
}

function writeUtf8(relPath, text) {
  mkdirp(relPath.replace(/\/[^/]+$/, ""));
  fs.writeFileSync(relPath, text);
}

function runPropsFromStyle(style) {
  if (!style) {
    return "";
  }
  var parts = [];
  if (style.font) {
    parts.push(
      '<w:rFonts w:ascii="' + xmlEscape(style.font) + '" w:hAnsi="' + xmlEscape(style.font) + '"/>'
    );
  }
  if (style.sizePt != null) {
    var hp = ptToHalfPoints(style.sizePt);
    parts.push('<w:sz w:val="' + hp + '"/><w:szCs w:val="' + hp + '"/>');
  }
  if (style.bold) {
    parts.push("<w:b/>");
  }
  if (style.italic) {
    parts.push("<w:i/>");
  }
  return parts.join("");
}

function spacingFromStyle(style) {
  if (!style || style.lineSpacing == null) {
    return null;
  }
  return { line: lineSpacingToW(style.lineSpacing) };
}

function buildParagraph(block, defaultStyle) {
  var style = block.style || defaultStyle || {};
  var pPrParts = [];
  if (block.type === "heading") {
    pPrParts.push('<w:pStyle w:val="Heading' + Math.min(9, Math.max(1, block.level || 1)) + '"/>');
  } else if (block.pStyle) {
    pPrParts.push('<w:pStyle w:val="' + xmlEscape(block.pStyle) + '"/>');
  }
  var sp = spacingFromStyle(style);
  if (sp) {
    pPrParts.push('<w:spacing w:line="' + sp.line + '" w:lineRule="auto"/>');
  }
  var pPr = pPrParts.length ? "<w:pPr>" + pPrParts.join("") + "</w:pPr>" : "";
  var text = block.type === "bullet" ? "• " + block.text : block.text;
  var rPr = runPropsFromStyle(style);
  var rPrXml = rPr ? "<w:rPr>" + rPr + "</w:rPr>" : "";
  var t = xmlEscape(text);
  var space = text.indexOf(" ") >= 0 ? ' xml:space="preserve"' : "";
  return "<w:p>" + pPr + "<w:r>" + rPrXml + "<w:t" + space + ">" + t + "</w:t></w:r></w:p>";
}

function renderDocumentXml(doc) {
  var body = "";
  for (var i = 0; i < doc.blocks.length; i++) {
    body += buildParagraph(doc.blocks[i], doc.defaultStyle);
  }
  var sectPr =
    '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
    + '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>';
  return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    + '<w:document xmlns:w="' + W_NS + '"><w:body>' + body + sectPr + "</w:body></w:document>";
}

function writeDocxTree(buildDir, doc) {
  writeUtf8(
    buildDir + "/[Content_Types].xml",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      + '<Default Extension="xml" ContentType="application/xml"/>'
      + '<Override PartName="/word/document.xml"'
      + ' ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
      + '<Override PartName="/docProps/core.xml"'
      + ' ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
      + "</Types>"
  );
  writeUtf8(
    buildDir + "/_rels/.rels",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
      + '<Relationship Id="rId1"'
      + ' Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"'
      + ' Target="word/document.xml"/>'
      + '<Relationship Id="rId2"'
      + ' Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"'
      + ' Target="docProps/core.xml"/>'
      + "</Relationships>"
  );
  writeUtf8(buildDir + "/word/document.xml", renderDocumentXml(doc));
  writeUtf8(
    buildDir + "/word/_rels/document.xml.rels",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>'
  );
  writeUtf8(
    buildDir + "/docProps/core.xml",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"'
      + ' xmlns:dc="http://purl.org/dc/elements/1.1/">'
      + "<dc:title>" + xmlEscape(doc.title || "Document") + "</dc:title>"
      + "<dc:creator>Weizhi</dc:creator></cp:coreProperties>"
  );
}

export function Document(options) {
  options = options || {};
  var doc = {
    title: options.title || "",
    defaultStyle: options.defaultStyle || {},
    blocks: [],
  };
  doc.addHeading = function (text, level) {
    doc.blocks.push({ type: "heading", level: level || 1, text: String(text) });
    return doc;
  };
  doc.addParagraph = function (text, style) {
    doc.blocks.push({
      type: "paragraph",
      text: String(text),
      style: style ? Object.assign({}, style) : undefined,
    });
    return doc;
  };
  doc.addBullet = function (text, style) {
    doc.blocks.push({
      type: "bullet",
      text: String(text),
      style: style ? Object.assign({}, style) : undefined,
    });
    return doc;
  };
  doc.setDefaultStyle = function (style) {
    doc.defaultStyle = Object.assign({}, doc.defaultStyle, style || {});
    return doc;
  };
  return doc;
}

export function documentFromMarkdown(md, options) {
  options = options || {};
  var doc = Document({ title: options.title || "", defaultStyle: options.defaultStyle });
  if (options.title) {
    doc.addHeading(options.title, 1);
  }
  if (md == null) {
    md = "";
  }
  md = String(md).replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  var lines = md.split("\n");
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].replace(/\s+$/, "");
    if (!line) {
      continue;
    }
    if (line.startsWith("#")) {
      var level = 0;
      while (level < line.length && line.charAt(level) === "#") {
        level++;
      }
      if (level < line.length && line.charAt(level) === " ") {
        doc.addHeading(line.slice(level + 1).trim(), level);
        continue;
      }
    }
    if (line.startsWith("- ") || line.startsWith("* ")) {
      doc.addBullet(line.slice(2).trim());
      continue;
    }
    doc.addParagraph(line.trim());
  }
  return doc;
}

export function renderDocx(doc, outputPath) {
  if (!outputPath) {
    throw new Error("bad argument: renderDocx: outputPath required");
  }
  var buildDir = "tmp/docx-render-" + Date.now();
  writeDocxTree(buildDir, doc);
  packDir(buildDir, outputPath);
  var bytes = fs.readFileSync(outputPath).length;
  return { ok: true, path: outputPath, bytes: bytes };
}

/** Read markdown from workspace path or inline string, render to docx. */
export function markdownToDocx(options) {
  options = options || {};
  var outputPath = options.outputPath;
  if (!outputPath) {
    throw new Error("bad argument: markdownToDocx: outputPath required");
  }
  var md = options.markdown != null ? String(options.markdown) : "";
  if (options.inputPath) {
    md = fs.readFileSync(options.inputPath).toString();
  }
  if (!md && !options.title) {
    throw new Error("bad argument: markdownToDocx: inputPath, markdown, or title required");
  }
  var doc = documentFromMarkdown(md, {
    title: options.title,
    defaultStyle: options.defaultStyle,
  });
  return renderDocx(doc, outputPath);
}

Document.create = function (options) {
  return Document(options);
};

export default {
  Document: Document,
  documentFromMarkdown: documentFromMarkdown,
  renderDocx: renderDocx,
  markdownToDocx: markdownToDocx,
};
