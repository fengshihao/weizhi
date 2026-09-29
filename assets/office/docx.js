/**
 * docx.js — Document model, Markdown import, OOXML render, load/edit/save.
 *
 * import { Document, markdownToDocx, readDocx } from "./docx.js";
 */
var fs = require("fs");
var zip = require("zip");

var W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
var R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
var WP_NS = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
var A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
var PIC_NS = "http://schemas.openxmlformats.org/drawingml/2006/picture";
var REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";

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

function unpack(docxPath, workDir) {
  var files = platformFiles();
  if (files && files.zipExtract) {
    files.zipExtract(docxPath, workDir);
    return;
  }
  zip.extractSync(docxPath, workDir);
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
    throw new Error("docx: platform files.mkdir required (install caps)");
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
  var parent = relPath.replace(/\/[^/]+$/, "");
  if (parent && parent !== relPath) {
    mkdirp(parent);
  }
  fs.writeFileSync(relPath, text);
}

function writeBytes(relPath, bytes) {
  var parent = relPath.replace(/\/[^/]+$/, "");
  if (parent && parent !== relPath) {
    mkdirp(parent);
  }
  fs.writeFileSync(relPath, bytes);
}

function readUtf8(relPath) {
  return fs.readFileSync(relPath).toString();
}

/** Parse inline **bold**, *italic*, `code`, [text](url) into run list. */
function parseInlines(text) {
  var runs = [];
  var s = String(text);
  var re = /(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`|\[[^\]]+\]\([^)]+\))/g;
  var last = 0;
  var m;
  while ((m = re.exec(s)) !== null) {
    if (m.index > last) {
      runs.push({ type: "text", text: s.slice(last, m.index) });
    }
    var tok = m[0];
    if (tok.indexOf("**") === 0) {
      runs.push({ type: "text", text: tok.slice(2, -2), bold: true });
    } else if (tok.indexOf("*") === 0) {
      runs.push({ type: "text", text: tok.slice(1, -1), italic: true });
    } else if (tok.indexOf("`") === 0) {
      runs.push({ type: "text", text: tok.slice(1, -1), code: true });
    } else if (tok.indexOf("[") === 0) {
      var lm = tok.match(/^\[([^\]]*)\]\(([^)]+)\)$/);
      if (lm) {
        runs.push({ type: "link", text: lm[1], href: lm[2] });
      } else {
        runs.push({ type: "text", text: tok });
      }
    }
    last = m.index + tok.length;
  }
  if (last < s.length) {
    runs.push({ type: "text", text: s.slice(last) });
  }
  if (runs.length === 0) {
    runs.push({ type: "text", text: s });
  }
  return runs;
}

function inlinesToPlain(inlines) {
  var out = "";
  for (var i = 0; i < inlines.length; i++) {
    var r = inlines[i];
    if (r.type === "link") {
      out += r.text;
    } else {
      out += r.text;
    }
  }
  return out;
}

function escapeRegExp(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function blockPlainText(block) {
  if (!block) {
    return "";
  }
  if (block.type === "code") {
    return String(block.text || "");
  }
  if (block.type === "table") {
    var rows = block.rows || [];
    return rows
      .map(function (row) {
        return row.join("\t");
      })
      .join("\n");
  }
  if (block.type === "image") {
    return block.path ? String(block.path) : "";
  }
  return inlinesToPlain(block.inlines || []);
}

function setBlockPlainText(block, text) {
  if (block.type === "code") {
    block.text = String(text);
    return;
  }
  if (block.type === "table") {
    throw new Error("docx: setBlockText on table: edit block.rows in memory or replace table cell text via grep+replace on plain export");
  }
  if (block.type === "image") {
    throw new Error("docx: setBlockText on image block not supported");
  }
  block.inlines = parseInlines(String(text));
}

function blockMatchesType(block, typeFilter) {
  if (!typeFilter) {
    return true;
  }
  if (typeof typeFilter === "string") {
    return block.type === typeFilter;
  }
  if (Object.prototype.toString.call(typeFilter) === "[object Array]") {
    for (var i = 0; i < typeFilter.length; i++) {
      if (block.type === typeFilter[i]) {
        return true;
      }
    }
    return false;
  }
  return true;
}

function snippetAround(text, start, len, radius) {
  radius = radius == null ? 48 : radius;
  var a = Math.max(0, start - radius);
  var b = Math.min(text.length, start + len + radius);
  return (a > 0 ? "…" : "") + text.slice(a, b) + (b < text.length ? "…" : "");
}

function buildSearchRegExp(pattern, options) {
  options = options || {};
  var flags = "";
  if (options.caseInsensitive !== false) {
    flags += "i";
  }
  if (options.global !== false) {
    flags += "g";
  }
  if (pattern instanceof RegExp) {
    return new RegExp(pattern.source, flags || pattern.flags);
  }
  if (options.regex) {
    return new RegExp(String(pattern), flags);
  }
  return new RegExp(escapeRegExp(String(pattern)), flags);
}

function countRegExpMatches(text, re) {
  var copy = new RegExp(re.source, re.flags.indexOf("g") >= 0 ? re.flags : re.flags + "g");
  var n = 0;
  var m;
  while ((m = copy.exec(text)) !== null) {
    n++;
    if (m[0].length === 0) {
      copy.lastIndex++;
    }
  }
  return n;
}

function mergeStyle(base, override) {
  var out = Object.assign({}, base || {});
  if (override) {
    Object.assign(out, override);
  }
  return out;
}

function effectiveBlockStyle(doc, block) {
  if (!block) {
    return {};
  }
  if (block.type === "code") {
    return { font: "Consolas", sizePt: 10 };
  }
  return mergeStyle(doc.defaultStyle, block.style);
}

function styleLabel(style) {
  if (!style) {
    return "";
  }
  var parts = [];
  if (style.font) {
    parts.push(String(style.font));
  }
  if (style.sizePt != null) {
    parts.push(String(style.sizePt) + "pt");
  }
  if (style.bold) {
    parts.push("bold");
  }
  if (style.italic) {
    parts.push("italic");
  }
  if (style.lineSpacing != null) {
    parts.push("line=" + style.lineSpacing);
  }
  return parts.length ? " {" + parts.join(", ") + "}" : "";
}

function styleMatchesCriteria(doc, block, criteria) {
  if (!criteria) {
    return true;
  }
  var eff = effectiveBlockStyle(doc, block);
  if (criteria.wordStyle != null) {
    var ws = block.wordStyle || "";
    if (String(criteria.wordStyle).toLowerCase() !== ws.toLowerCase()) {
      return false;
    }
  }
  if (criteria.font != null) {
    var f = eff.font ? String(eff.font) : "";
    if (f.toLowerCase().indexOf(String(criteria.font).toLowerCase()) < 0) {
      return false;
    }
  }
  if (criteria.sizePt != null && eff.sizePt !== criteria.sizePt) {
    return false;
  }
  if (criteria.minSizePt != null && (eff.sizePt == null || eff.sizePt < criteria.minSizePt)) {
    return false;
  }
  if (criteria.maxSizePt != null && (eff.sizePt == null || eff.sizePt > criteria.maxSizePt)) {
    return false;
  }
  if (criteria.bold != null && !!eff.bold !== !!criteria.bold) {
    return false;
  }
  if (criteria.italic != null && !!eff.italic !== !!criteria.italic) {
    return false;
  }
  if (criteria.lineSpacing != null && eff.lineSpacing !== criteria.lineSpacing) {
    return false;
  }
  return true;
}

function summarizeInlineStyles(inlines) {
  var runs = inlines || [];
  var out = [];
  for (var i = 0; i < runs.length; i++) {
    var r = runs[i];
    out.push({
      index: i,
      type: r.type || "text",
      text: r.type === "link" ? r.text : r.text,
      bold: !!r.bold,
      italic: !!r.italic,
      code: !!r.code,
      href: r.href,
    });
  }
  return out;
}

function extractParagraphStyleFromPInner(pInner) {
  var style = {};
  var wordStyle = null;
  var pPrM = pInner.match(/<w:pPr>([\s\S]*?)<\/w:pPr>/);
  var pPr = pPrM ? pPrM[1] : "";
  var pStyleM = pPr.match(/<w:pStyle w:val="([^"]+)"/);
  if (!pStyleM) {
    pStyleM = pInner.match(/<w:pStyle w:val="([^"]+)"/);
  }
  if (pStyleM) {
    wordStyle = pStyleM[1];
  }
  var spM = pPr.match(/<w:spacing[^>]*\bw:line="(\d+)"/);
  if (spM) {
    style.lineSpacing = Number(spM[1]) / 240;
  }
  var runRe = /<w:r[\s\S]*?<\/w:r>/g;
  var rm;
  while ((rm = runRe.exec(pInner)) !== null) {
    if (rm[0].indexOf("<w:drawing") >= 0) {
      continue;
    }
    var text = extractPlainFromRuns(rm[0]);
    if (!text) {
      continue;
    }
    var rPrM = rm[0].match(/<w:rPr>([\s\S]*?)<\/w:rPr>/);
    var rPr = rPrM ? rPrM[1] : "";
    var fontM = rPr.match(/w:ascii="([^"]+)"/);
    if (fontM) {
      style.font = fontM[1];
    }
    var szM = rPr.match(/<w:sz w:val="(\d+)"/);
    if (szM) {
      style.sizePt = Number(szM[1]) / 2;
    }
    if (rPr.indexOf("<w:b") >= 0) {
      style.bold = true;
    }
    if (rPr.indexOf("<w:i") >= 0) {
      style.italic = true;
    }
    break;
  }
  var hasKeys = false;
  for (var k in style) {
    if (Object.prototype.hasOwnProperty.call(style, k)) {
      hasKeys = true;
      break;
    }
  }
  return {
    style: hasKeys ? style : undefined,
    wordStyle: wordStyle,
  };
}

function runPropsFromStyle(style, run) {
  var parts = [];
  if (style && style.font) {
    parts.push(
      '<w:rFonts w:ascii="' + xmlEscape(style.font) + '" w:hAnsi="' + xmlEscape(style.font) + '"/>'
    );
  }
  if (style && style.sizePt != null) {
    var hp = ptToHalfPoints(style.sizePt);
    parts.push('<w:sz w:val="' + hp + '"/><w:szCs w:val="' + hp + '"/>');
  }
  if ((run && run.bold) || (style && style.bold)) {
    parts.push("<w:b/>");
  }
  if ((run && run.italic) || (style && style.italic)) {
    parts.push("<w:i/>");
  }
  if (run && run.code) {
    parts.push('<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>');
  }
  return parts.join("");
}

function buildRunXml(text, style, run) {
  var rPr = runPropsFromStyle(style, run);
  var rPrXml = rPr ? "<w:rPr>" + rPr + "</w:rPr>" : "";
  var t = xmlEscape(text);
  var space = /[\s\t]/.test(text) ? ' xml:space="preserve"' : "";
  return "<w:r>" + rPrXml + "<w:t" + space + ">" + t + "</w:t></w:r>";
}

function RenderState(doc) {
  this.doc = doc;
  this.hyperlinkId = 10;
  this.imageId = 1;
  this.hyperlinks = [];
  this.images = [];
}

RenderState.prototype.nextHyperlink = function (href) {
  var id = "rId" + this.hyperlinkId++;
  this.hyperlinks.push({ id: id, href: href });
  return id;
};

RenderState.prototype.addImage = function (workspacePath, widthPx, heightPx) {
  var ext = "png";
  var m = workspacePath.match(/\.(jpe?g|png|gif|webp)$/i);
  if (m) {
    ext = m[1].toLowerCase() === "jpeg" ? "jpeg" : m[1].toLowerCase();
  }
  var mediaName = "image" + this.imageId + "." + ext;
  this.imageId++;
  var bytes = fs.readFileSync(workspacePath);
  var relId = "rId" + (100 + this.images.length);
  this.images.push({
    relId: relId,
    mediaName: mediaName,
    bytes: bytes,
    width: widthPx || 320,
    height: heightPx || 240,
  });
  return relId;
};

function buildDrawing(relId, cx, cy) {
  return "<w:r><w:drawing>"
    + '<wp:inline distT="0" distB="0" distL="0" distR="0" xmlns:wp="' + WP_NS + '">'
    + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
    + "<wp:docPr id=\"1\" name=\"Picture\"/>"
    + "<a:graphic xmlns:a=\"" + A_NS + "\"><a:graphicData uri=\"" + PIC_NS + "\">"
    + "<pic:pic xmlns:pic=\"" + PIC_NS + "\">"
    + "<pic:nvPicPr><pic:cNvPr id=\"0\" name=\"\"/><pic:cNvPicPr/></pic:nvPicPr>"
    + "<pic:blipFill><a:blip r:embed=\"" + relId + "\" xmlns:r=\"" + R_NS + "\"/>"
    + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
    + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy
    + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/></pic:spPr>"
    + "</pic:pic></a:graphicData></a:graphic>"
    + "</wp:inline></w:drawing></w:r>";
}

function buildParagraphBlock(block, defaultStyle, state) {
  var style = block.style || defaultStyle || {};
  var pPrParts = [];
  if (block.type === "heading") {
    pPrParts.push('<w:pStyle w:val="Heading' + Math.min(9, Math.max(1, block.level || 1)) + '"/>');
  }
  var sp = style.lineSpacing != null ? { line: lineSpacingToW(style.lineSpacing) } : null;
  if (sp) {
    pPrParts.push('<w:spacing w:line="' + sp.line + '" w:lineRule="auto"/>');
  }
  var pPr = pPrParts.length ? "<w:pPr>" + pPrParts.join("") + "</w:pPr>" : "";
  var runs = "";
  var inlines = block.inlines || parseInlines(block.text || "");
  if (block.type === "bullet") {
    inlines = [{ type: "text", text: "• " + inlinesToPlain(inlines) }];
  }
  if (block.type === "task") {
    var mark = block.checked ? "☑ " : "☐ ";
    inlines = [{ type: "text", text: mark + inlinesToPlain(inlines) }];
  }
  if (block.type === "blockquote") {
    inlines = [{ type: "text", text: inlinesToPlain(inlines), italic: true }];
  }
  for (var i = 0; i < inlines.length; i++) {
    var run = inlines[i];
    if (run.type === "link") {
      var hid = state.nextHyperlink(run.href);
      runs += "<w:hyperlink r:id=\"" + hid + "\">" + buildRunXml(run.text, style, { bold: true }) + "</w:hyperlink>";
    } else {
      runs += buildRunXml(run.text, style, run);
    }
  }
  return "<w:p>" + pPr + runs + "</w:p>";
}

function buildCodeBlock(block) {
  var lines = String(block.text || "").split("\n");
  var xml = "";
  for (var i = 0; i < lines.length; i++) {
    xml += "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/></w:pPr>"
      + buildRunXml(lines[i], { font: "Consolas", sizePt: 10 }, { code: true }) + "</w:p>";
  }
  return xml;
}

function buildTableBlock(block) {
  var rows = block.rows || [];
  if (rows.length === 0) {
    return "";
  }
  var cols = rows[0].length;
  var tbl =
    "<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblBorders>"
    + "<w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\" w:sz=\"4\"/>"
    + "<w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/>"
    + "<w:insideH w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/>"
    + "</w:tblBorders></w:tblPr>";
  for (var r = 0; r < rows.length; r++) {
    tbl += "<w:tr>";
    for (var c = 0; c < cols; c++) {
      var cell = rows[r][c] != null ? String(rows[r][c]) : "";
      tbl += "<w:tc><w:tcPr><w:tcW w:w=\"2000\" w:type=\"dxa\"/></w:tcPr>"
        + "<w:p>" + buildRunXml(cell, {}, {}) + "</w:p></w:tc>";
    }
    tbl += "</w:tr>";
  }
  tbl += "</w:tbl>";
  return tbl;
}

function buildImageBlock(block, state) {
  var cx = Math.round((block.width || 320) * 9525);
  var cy = Math.round((block.height || 240) * 9525);
  var relId = state.addImage(block.path, block.width, block.height);
  return "<w:p>" + buildDrawing(relId, cx, cy) + "</w:p>";
}

function renderBodyXml(doc, state) {
  var body = "";
  for (var i = 0; i < doc.blocks.length; i++) {
    var b = doc.blocks[i];
    if (b.type === "table") {
      body += buildTableBlock(b);
    } else if (b.type === "code") {
      body += buildCodeBlock(b);
    } else if (b.type === "image") {
      body += buildImageBlock(b, state);
    } else {
      body += buildParagraphBlock(b, doc.defaultStyle, state);
    }
  }
  return body;
}

function buildDocumentRels(state) {
  var xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    + '<Relationships xmlns="' + REL_NS + '">';
  for (var i = 0; i < state.hyperlinks.length; i++) {
    var h = state.hyperlinks[i];
    xml += '<Relationship Id="' + h.id + '" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"'
      + ' Target="' + xmlEscape(h.href) + '" TargetMode="External"/>';
  }
  for (var j = 0; j < state.images.length; j++) {
    var img = state.images[j];
    xml += '<Relationship Id="' + img.relId + '" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"'
      + ' Target="media/' + img.mediaName + '"/>';
  }
  xml += "</Relationships>";
  return xml;
}

function buildContentTypes(state) {
  var xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    + '<Default Extension="xml" ContentType="application/xml"/>'
    + '<Default Extension="png" ContentType="image/png"/>'
    + '<Default Extension="jpeg" ContentType="image/jpeg"/>'
    + '<Default Extension="jpg" ContentType="image/jpeg"/>'
    + '<Override PartName="/word/document.xml"'
    + ' ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    + '<Override PartName="/docProps/core.xml"'
    + ' ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>'
    + "</Types>";
  return xml;
}

function writeDocxTree(buildDir, doc) {
  var state = new RenderState(doc);
  var body = renderBodyXml(doc, state);
  var sectPr =
    '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
    + '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>';
  var documentXml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    + '<w:document xmlns:w="' + W_NS + '"><w:body>' + body + sectPr + "</w:body></w:document>";

  writeUtf8(buildDir + "/[Content_Types].xml", buildContentTypes(state));
  writeUtf8(
    buildDir + "/_rels/.rels",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<Relationships xmlns="' + REL_NS + '">'
      + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
      + '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>'
      + "</Relationships>"
  );
  writeUtf8(buildDir + "/word/document.xml", documentXml);
  writeUtf8(buildDir + "/word/_rels/document.xml.rels", buildDocumentRels(state));
  for (var k = 0; k < state.images.length; k++) {
    var im = state.images[k];
    writeBytes(buildDir + "/word/media/" + im.mediaName, im.bytes);
  }
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
    _sourcePath: options._sourcePath || null,
  };
  doc.addHeading = function (text, level) {
    doc.blocks.push({
      type: "heading",
      level: level || 1,
      inlines: parseInlines(String(text)),
    });
    return doc;
  };
  doc.addParagraph = function (textOrInlines, style) {
    var inlines = typeof textOrInlines === "string" ? parseInlines(textOrInlines) : textOrInlines;
    doc.blocks.push({ type: "paragraph", inlines: inlines, style: style ? Object.assign({}, style) : undefined });
    return doc;
  };
  doc.addBullet = function (text, style) {
    doc.blocks.push({
      type: "bullet",
      inlines: parseInlines(String(text)),
      style: style ? Object.assign({}, style) : undefined,
    });
    return doc;
  };
  doc.addTask = function (text, checked, style) {
    doc.blocks.push({
      type: "task",
      checked: !!checked,
      inlines: parseInlines(String(text)),
      style: style ? Object.assign({}, style) : undefined,
    });
    return doc;
  };
  doc.addBlockquote = function (text) {
    doc.blocks.push({ type: "blockquote", inlines: parseInlines(String(text)) });
    return doc;
  };
  doc.addCode = function (text) {
    doc.blocks.push({ type: "code", text: String(text) });
    return doc;
  };
  doc.addTable = function (rows) {
    doc.blocks.push({ type: "table", rows: rows });
    return doc;
  };
  doc.addImage = function (workspacePath, width, height) {
    doc.blocks.push({
      type: "image",
      path: workspacePath,
      width: width || 320,
      height: height || 240,
    });
    return doc;
  };
  doc.setDefaultStyle = function (style) {
    doc.defaultStyle = Object.assign({}, doc.defaultStyle, style || {});
    return doc;
  };
  doc.save = function (outputPath) {
    return renderDocx(doc, outputPath);
  };
  doc.toMarkdown = function () {
    return blocksToMarkdown(doc.blocks);
  };
  doc.plainText = function () {
    var parts = [];
    for (var pi = 0; pi < doc.blocks.length; pi++) {
      parts.push(blockPlainText(doc.blocks[pi]));
    }
    return parts.join("\n");
  };
  doc.textView = function (options) {
    options = options || {};
    var lines = [];
    for (var i = 0; i < doc.blocks.length; i++) {
      var b = doc.blocks[i];
      if (!blockMatchesType(b, options.type)) {
        continue;
      }
      var plain = blockPlainText(b);
      var label = b.type === "heading" ? "h" + (b.level || 1) : b.type;
      var head = "[" + i + "] " + label + ": ";
      if (options.includeStyle) {
        var eff = effectiveBlockStyle(doc, b);
        head = "[" + i + "] " + label + styleLabel(eff) + (b.wordStyle ? " <" + b.wordStyle + ">" : "") + ": ";
      }
      if (b.type === "code" && plain.indexOf("\n") >= 0) {
        var codeLines = plain.split("\n");
        for (var cl = 0; cl < codeLines.length; cl++) {
          lines.push({
            blockIndex: i,
            type: b.type,
            level: b.level,
            line: lines.length + 1,
            text: cl === 0 ? head + codeLines[cl] : head.replace(/: $/, ": (cont) ") + codeLines[cl],
            preview: codeLines[cl],
          });
        }
      } else {
        lines.push({
          blockIndex: i,
          type: b.type,
          level: b.level,
          line: lines.length + 1,
          text: head + plain,
          preview: plain,
        });
      }
    }
    return lines;
  };
  doc.listBlocks = function (options) {
    options = options || {};
    var out = [];
    for (var i = 0; i < doc.blocks.length; i++) {
      var bl = doc.blocks[i];
      if (!blockMatchesType(bl, options.type)) {
        continue;
      }
      if (!styleMatchesCriteria(doc, bl, options.style)) {
        continue;
      }
      var item = {
        index: i,
        type: bl.type,
        text: blockPlainText(bl),
      };
      if (options.includeStyle !== false) {
        item.style = effectiveBlockStyle(doc, bl);
        if (bl.wordStyle) {
          item.wordStyle = bl.wordStyle;
        }
      }
      if (bl.type === "heading") {
        item.level = bl.level || 1;
      }
      if (bl.type === "task") {
        item.checked = !!bl.checked;
      }
      out.push(item);
    }
    return out;
  };
  doc.headings = function () {
    return doc.listBlocks({ type: "heading" });
  };
  doc.filter = function (options) {
    return doc.listBlocks(options || {});
  };
  doc.getBlock = function (index) {
    var b = doc.blocks[index];
    if (!b) {
      return null;
    }
    var info = {
      index: index,
      type: b.type,
      text: blockPlainText(b),
      level: b.level,
      checked: b.checked,
      style: effectiveBlockStyle(doc, b),
    };
    if (b.wordStyle) {
      info.wordStyle = b.wordStyle;
    }
    return info;
  };
  doc.getBlockStyle = function (index) {
    var b = doc.blocks[index];
    if (!b) {
      return null;
    }
    return {
      blockIndex: index,
      type: b.type,
      effective: effectiveBlockStyle(doc, b),
      explicit: b.style ? mergeStyle({}, b.style) : null,
      wordStyle: b.wordStyle || null,
      defaultStyle: mergeStyle({}, doc.defaultStyle),
      inlines: summarizeInlineStyles(b.inlines),
    };
  };
  doc.setBlockStyle = function (index, style) {
    var b = doc.blocks[index];
    if (!b) {
      throw new Error("docx: setBlockStyle: invalid block index " + index);
    }
    if (b.type === "table" || b.type === "image") {
      throw new Error("docx: setBlockStyle not supported for " + b.type);
    }
    b.style = mergeStyle(b.style, style || {});
    return doc;
  };
  doc.grepStyles = function (criteria, options) {
    options = options || {};
    criteria = criteria || {};
    var hits = [];
    for (var i = 0; i < doc.blocks.length; i++) {
      var bl = doc.blocks[i];
      if (!blockMatchesType(bl, options.type)) {
        continue;
      }
      if (!styleMatchesCriteria(doc, bl, criteria)) {
        continue;
      }
      hits.push({
        blockIndex: i,
        type: bl.type,
        text: blockPlainText(bl),
        style: effectiveBlockStyle(doc, bl),
        wordStyle: bl.wordStyle || null,
      });
    }
    return hits;
  };
  doc.setBlockText = function (index, text) {
    var b = doc.blocks[index];
    if (!b) {
      throw new Error("docx: setBlockText: invalid block index " + index);
    }
    setBlockPlainText(b, text);
    return doc;
  };
  doc.grep = function (pattern, options) {
    options = options || {};
    var re = buildSearchRegExp(pattern, Object.assign({}, options, { global: true }));
    var max = options.maxResults != null ? options.maxResults : 200;
    var matches = [];
    var view = doc.textView({ type: options.type });
    for (var vi = 0; vi < view.length; vi++) {
      var row = view[vi];
      var plain = row.preview;
      var localRe = new RegExp(re.source, re.flags);
      var m;
      while ((m = localRe.exec(plain)) !== null) {
        matches.push({
          blockIndex: row.blockIndex,
          type: row.type,
          line: row.line,
          match: m[0],
          start: m.index,
          end: m.index + m[0].length,
          text: plain,
          snippet: snippetAround(plain, m.index, m[0].length),
        });
        if (matches.length >= max) {
          return { matches: matches, truncated: true };
        }
        if (m[0].length === 0) {
          localRe.lastIndex++;
        }
      }
    }
    return { matches: matches, truncated: false };
  };
  doc.replaceInBlock = function (blockIndex, search, replacement, options) {
    options = options || {};
    var b = doc.blocks[blockIndex];
    if (!b) {
      throw new Error("docx: replaceInBlock: invalid block index " + blockIndex);
    }
    var plain = blockPlainText(b);
    var reOpts = Object.assign({}, options, { global: options.replaceFirst ? false : options.global !== false });
    var re = buildSearchRegExp(search, reOpts);
    var count = options.replaceFirst
      ? (new RegExp(re.source, re.flags.replace("g", "")).test(plain) ? 1 : 0)
      : countRegExpMatches(plain, re);
    if (count === 0) {
      return { replaced: 0, blockIndex: blockIndex };
    }
    var newPlain = plain.replace(re, String(replacement));
    setBlockPlainText(b, newPlain);
    return { replaced: count, blockIndex: blockIndex };
  };
  doc.replaceAll = function (search, replacement, options) {
    options = options || {};
    var total = 0;
    var touched = [];
    for (var i = 0; i < doc.blocks.length; i++) {
      if (!blockMatchesType(doc.blocks[i], options.type)) {
        continue;
      }
      var r = doc.replaceInBlock(i, search, replacement, options);
      if (r.replaced > 0) {
        total += r.replaced;
        touched.push(i);
      }
    }
    return { replaced: total, blockIndices: touched };
  };
  return doc;
}

Document.create = function (options) {
  return Document(options);
};

function blocksToMarkdown(blocks) {
  var lines = [];
  for (var i = 0; i < blocks.length; i++) {
    var b = blocks[i];
    if (b.type === "heading") {
      var h = "";
      for (var k = 0; k < (b.level || 1); k++) {
        h += "#";
      }
      lines.push(h + " " + inlinesToPlain(b.inlines || []));
    } else if (b.type === "bullet") {
      lines.push("- " + inlinesToPlain(b.inlines || []));
    } else if (b.type === "task") {
      lines.push("- [" + (b.checked ? "x" : " ") + "] " + inlinesToPlain(b.inlines || []));
    } else if (b.type === "blockquote") {
      lines.push("> " + inlinesToPlain(b.inlines || []));
    } else if (b.type === "code") {
      lines.push("```");
      lines.push(b.text || "");
      lines.push("```");
    } else if (b.type === "table") {
      var rows = b.rows || [];
      for (var r = 0; r < rows.length; r++) {
        lines.push("| " + rows[r].join(" | ") + " |");
        if (r === 0) {
          lines.push("| " + rows[r].map(function () { return "---"; }).join(" | ") + " |");
        }
      }
    } else if (b.type === "image") {
      lines.push("![](" + b.path + ")");
    } else {
      lines.push(inlinesToPlain(b.inlines || parseInlines(b.text || "")));
    }
    lines.push("");
  }
  return lines.join("\n").replace(/\n\n+$/, "\n");
}

function isTableRow(line) {
  return /^\|(.+)\|$/.test(line.trim());
}

function parseTableRow(line) {
  var inner = line.trim().slice(1, -1);
  return inner.split("|").map(function (c) { return c.trim(); });
}

function isTableSeparator(line) {
  return /^\|\s*:?-+:?\s*(\|\s*:?-+:?\s*)+\|$/.test(line.trim());
}

export function documentFromMarkdown(md, options) {
  options = options || {};
  var doc = Document({ title: options.title || "", defaultStyle: options.defaultStyle });
  if (options.title) {
    doc.addHeading(options.title, 1);
  }
  md = String(md == null ? "" : md).replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  var lines = md.split("\n");
  var i = 0;
  while (i < lines.length) {
    var line = lines[i].replace(/\s+$/, "");
    if (!line) {
      i++;
      continue;
    }
    if (line.startsWith("```")) {
      i++;
      var codeLines = [];
      while (i < lines.length && !lines[i].startsWith("```")) {
        codeLines.push(lines[i]);
        i++;
      }
      if (i < lines.length) {
        i++;
      }
      doc.addCode(codeLines.join("\n"));
      continue;
    }
    if (isTableRow(line) && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      var tableRows = [parseTableRow(line)];
      i += 2;
      while (i < lines.length && isTableRow(lines[i])) {
        tableRows.push(parseTableRow(lines[i]));
        i++;
      }
      doc.addTable(tableRows);
      continue;
    }
    var imgM = line.match(/^!\[([^\]]*)\]\(([^)]+)\)\s*$/);
    if (imgM) {
      doc.addImage(imgM[2].trim());
      i++;
      continue;
    }
    if (line.startsWith("#")) {
      var level = 0;
      while (level < line.length && line.charAt(level) === "#") {
        level++;
      }
      if (level < line.length && line.charAt(level) === " ") {
        doc.addHeading(line.slice(level + 1).trim(), level);
        i++;
        continue;
      }
    }
    var taskM = line.match(/^[-*]\s+\[([ xX])\]\s+(.*)$/);
    if (taskM) {
      doc.addTask(taskM[2], taskM[1].toLowerCase() === "x");
      i++;
      continue;
    }
    if (line.startsWith("- ") || line.startsWith("* ")) {
      doc.addBullet(line.slice(2).trim());
      i++;
      continue;
    }
    if (line.startsWith("> ")) {
      doc.addBlockquote(line.slice(2).trim());
      i++;
      continue;
    }
    doc.addParagraph(line.trim());
    i++;
  }
  return doc;
}

function parseHyperlinkRuns(pInner, state) {
  var inlines = [];
  var re = /<w:hyperlink[^>]*r:id="([^"]+)"[^>]*>([\s\S]*?)<\/w:hyperlink>/g;
  var last = 0;
  var m;
  while ((m = re.exec(pInner)) !== null) {
    if (m.index > last) {
      inlines = inlines.concat(extractTextRuns(pInner.slice(last, m.index)));
    }
    var href = m[1];
    for (var hi = 0; hi < state.hyperlinks.length; hi++) {
      if (state.hyperlinks[hi].id === href) {
        href = state.hyperlinks[hi].href;
        break;
      }
    }
    var linkText = extractPlainFromRuns(m[2]);
    inlines.push({ type: "link", text: linkText, href: href });
    last = m.index + m[0].length;
  }
  if (last < pInner.length) {
    inlines = inlines.concat(extractTextRuns(pInner.slice(last)));
  }
  return inlines.length ? inlines : extractTextRuns(pInner);
}

function extractPlainFromRuns(fragment) {
  var texts = [];
  var re = /<w:t(?:\s+xml:space="preserve")?>([\s\S]*?)<\/w:t>/g;
  var m;
  while ((m = re.exec(fragment)) !== null) {
    texts.push(decodeXml(m[1]));
  }
  return texts.join("");
}

function decodeXml(s) {
  return s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&amp;/g, "&");
}

function extractTextRuns(fragment) {
  var inlines = [];
  var re = /<w:r[\s\S]*?<\/w:r>/g;
  var m;
  while ((m = re.exec(fragment)) !== null) {
    var run = m[0];
    if (run.indexOf("<w:drawing") >= 0) {
      continue;
    }
    var text = extractPlainFromRuns(run);
    if (!text) {
      continue;
    }
    var bold = run.indexOf("<w:b") >= 0;
    var italic = run.indexOf("<w:i") >= 0;
    inlines.push({ type: "text", text: text, bold: bold, italic: italic });
  }
  return inlines;
}

function parseDocumentXml(xml, relsMap) {
  var state = { hyperlinks: [] };
  for (var rid in relsMap) {
    if (relsMap[rid].type === "hyperlink") {
      state.hyperlinks.push({ id: rid, href: relsMap[rid].target });
    }
  }
  var blocks = [];
  var bodyMatch = xml.match(/<w:body>([\s\S]*?)<w:sectPr/);
  if (!bodyMatch) {
    throw new Error("bad docx: missing w:body");
  }
  var body = bodyMatch[1];
  var pos = 0;
  while (pos < body.length) {
    var tblStart = body.indexOf("<w:tbl", pos);
    var pStart = body.indexOf("<w:p", pos);
    if (pStart < 0 && tblStart < 0) {
      break;
    }
    if (tblStart >= 0 && (tblStart < pStart || pStart < 0)) {
      var tblEnd = body.indexOf("</w:tbl>", tblStart);
      if (tblEnd < 0) {
        break;
      }
      blocks.push(parseTableXml(body.slice(tblStart, tblEnd + 8)));
      pos = tblEnd + 8;
      continue;
    }
    var pEnd = body.indexOf("</w:p>", pStart);
    if (pEnd < 0) {
      break;
    }
    var pXml = body.slice(pStart, pEnd + 6);
    pos = pEnd + 6;
    if (pXml.indexOf("<w:drawing") >= 0) {
      blocks.push({ type: "image", path: "(embedded)", width: 320, height: 240 });
      continue;
    }
    var pInner = pXml.replace(/^<w:p[^>]*>/, "").replace(/<\/w:p>$/, "");
    var paraMeta = extractParagraphStyleFromPInner(pInner);
    var styleM = pInner.match(/<w:pStyle w:val="([^"]+)"/);
    var inlines = parseHyperlinkRuns(pInner, state);
    var plain = inlinesToPlain(inlines);
    var blockStyle = paraMeta.style ? mergeStyle({}, paraMeta.style) : undefined;
    var blockWordStyle = paraMeta.wordStyle || (styleM ? styleM[1] : null);
    if (styleM && /^Heading(\d)$/.test(styleM[1])) {
      blocks.push({
        type: "heading",
        level: parseInt(styleM[1], 10),
        inlines: inlines,
        style: blockStyle,
        wordStyle: blockWordStyle,
      });
    } else if (plain.indexOf("☐ ") === 0 || plain.indexOf("☑ ") === 0) {
      blocks.push({
        type: "task",
        checked: plain.indexOf("☑ ") === 0,
        inlines: parseInlines(plain.slice(2)),
        style: blockStyle,
        wordStyle: blockWordStyle,
      });
    } else if (plain.indexOf("• ") === 0) {
      blocks.push({
        type: "bullet",
        inlines: parseInlines(plain.slice(2)),
        style: blockStyle,
        wordStyle: blockWordStyle,
      });
    } else {
      blocks.push({
        type: "paragraph",
        inlines: inlines,
        style: blockStyle,
        wordStyle: blockWordStyle,
      });
    }
  }
  return blocks;
}

function parseTableXml(tblXml) {
  var rows = [];
  var trRe = /<w:tr[\s\S]*?<\/w:tr>/g;
  var tr;
  while ((tr = trRe.exec(tblXml)) !== null) {
    var cells = [];
    var tcRe = /<w:tc[\s\S]*?<\/w:tc>/g;
    var tc;
    while ((tc = tcRe.exec(tr[0])) !== null) {
      cells.push(extractPlainFromRuns(tc[0]));
    }
    if (cells.length) {
      rows.push(cells);
    }
  }
  return { type: "table", rows: rows };
}

function parseRels(relsXml) {
  var map = {};
  var re = /<Relationship Id="([^"]+)"[^>]*Target="([^"]+)"[^>]*Type="([^"]+)"/g;
  var m;
  while ((m = re.exec(relsXml)) !== null) {
    map[m[1]] = { target: m[2], type: m[3].indexOf("hyperlink") >= 0 ? "hyperlink" : "other" };
  }
  return map;
}

/** Unpack .docx to a workspace directory for raw OOXML editing with `fs`. */
export function unpackDocx(inputPath, options) {
  options = options || {};
  if (!inputPath) {
    throw new Error("bad argument: unpackDocx: inputPath required");
  }
  var workDir = options.workDir || "tmp/docx-unpack-" + Date.now();
  unpack(inputPath, workDir);
  return { ok: true, dir: workDir, inputPath: inputPath };
}

/** Pack a directory tree (OOXML parts) into a .docx zip. */
export function packDocx(sourceDir, outputPath) {
  if (!sourceDir || !outputPath) {
    throw new Error("bad argument: packDocx: sourceDir and outputPath required");
  }
  packDir(sourceDir, outputPath);
  var bytes = fs.readFileSync(outputPath).length;
  return { ok: true, path: outputPath, bytes: bytes };
}

export function readDocx(inputPath, options) {
  options = options || {};
  var workDir = options.workDir || "tmp/docx-read-" + Date.now();
  unpack(inputPath, workDir);
  var docXml = readUtf8(workDir + "/word/document.xml");
  var relsXml = "";
  try {
    relsXml = readUtf8(workDir + "/word/_rels/document.xml.rels");
  } catch (e) {
    relsXml = "";
  }
  var rels = parseRels(relsXml);
  var blocks = parseDocumentXml(docXml, rels);
  var doc = Document({ _sourcePath: inputPath });
  doc.blocks = blocks;
  doc._packDir = workDir;
  return doc;
}

Document.load = function (inputPath, options) {
  return readDocx(inputPath, options);
};

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

export default {
  Document: Document,
  documentFromMarkdown: documentFromMarkdown,
  renderDocx: renderDocx,
  markdownToDocx: markdownToDocx,
  readDocx: readDocx,
  unpackDocx: unpackDocx,
  packDocx: packDocx,
};
