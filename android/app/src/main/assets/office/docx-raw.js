/**
 * docx-raw.js — escape hatch: unpack → edit OOXML with fs → pack → validate.
 *
 * import { unpackDocx, packDocx, validateDocx } from "./docx-raw.js";
 */
import { unpackDocx, packDocx } from "./docx.js";

var fs = require("fs");

export { unpackDocx, packDocx };

var DOCX_REQUIRED_PARTS = ["[Content_Types].xml", "_rels/.rels", "word/document.xml"];

var DOCX_RECOMMENDED_PARTS = ["word/_rels/document.xml.rels", "docProps/core.xml"];

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

function pathExists(relPath) {
  try {
    fs.readFileSync(relPath);
    return true;
  } catch (e) {
    return false;
  }
}

function readText(relPath) {
  return fs.readFileSync(relPath).toString();
}

function isPkZipFile(relPath) {
  try {
    var buf = fs.readFileSync(relPath);
    return buf.length >= 2 && buf[0] === 0x50 && buf[1] === 0x4b;
  } catch (e) {
    return false;
  }
}

function listWorkspaceRelFiles(rootDir) {
  var files = platformFiles();
  if (!files || !files.list) {
    return null;
  }
  var out = [];
  function walk(rel) {
    var items = files.list(rel || ".");
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      var name = it.name;
      var child = rel && rel !== "." ? rel + "/" + name : name;
      if (it.dir) {
        walk(child);
      } else {
        out.push(child);
      }
    }
  }
  walk(rootDir || ".");
  return out;
}

function collectXmlPartPaths(dir) {
  var paths = [];
  var walked = listWorkspaceRelFiles(dir);
  if (walked) {
    for (var i = 0; i < walked.length; i++) {
      var p = walked[i];
      if (/\.(xml|rels)$/i.test(p)) {
        paths.push(p);
      }
    }
    return paths;
  }
  var fallback = DOCX_REQUIRED_PARTS.concat(DOCX_RECOMMENDED_PARTS);
  for (var j = 0; j < fallback.length; j++) {
    var rel = dir ? dir + "/" + fallback[j] : fallback[j];
    if (pathExists(rel)) {
      paths.push(rel);
    }
  }
  var ct = dir ? dir + "/[Content_Types].xml" : "[Content_Types].xml";
  if (pathExists(ct)) {
    try {
      var xml = readText(ct);
      var re = /PartName="(\/[^"]+)"/g;
      var m;
      while ((m = re.exec(xml)) !== null) {
        var part = m[1].replace(/^\//, "");
        var partPath = dir ? dir + "/" + part : part;
        if (pathExists(partPath) && paths.indexOf(partPath) < 0) {
          paths.push(partPath);
        }
      }
    } catch (e2) {
      /* ignore */
    }
  }
  return paths;
}

function lineColFromIndex(text, index) {
  var line = 1;
  var col = 1;
  for (var i = 0; i < index && i < text.length; i++) {
    if (text.charAt(i) === "\n") {
      line++;
      col = 1;
    } else {
      col++;
    }
  }
  return { line: line, column: col };
}

/** Lightweight well-formed XML check (not XSD / not Office semantics). */
export function checkXmlWellFormed(xml, fileLabel) {
  fileLabel = fileLabel || "xml";
  var s = String(xml);
  var i = 0;
  var len = s.length;
  var stack = [];

  function err(msg, at) {
    var pos = lineColFromIndex(s, at == null ? i : at);
    return {
      ok: false,
      file: fileLabel,
      message: msg,
      line: pos.line,
      column: pos.column,
    };
  }

  function isNameStart(c) {
    return /[A-Za-z_:]/.test(c);
  }
  function isNameChar(c) {
    return /[A-Za-z0-9_.:-]/.test(c);
  }

  function skipSpace() {
    while (i < len && /[\s\r\n\t]/.test(s.charAt(i))) {
      i++;
    }
  }

  function readName() {
    if (!isNameStart(s.charAt(i))) {
      return null;
    }
    var start = i;
    i++;
    while (i < len && isNameChar(s.charAt(i))) {
      i++;
    }
    return s.slice(start, i);
  }

  function skipDeclOrPI() {
    if (s.slice(i, i + 2) !== "<?") {
      return false;
    }
    var end = s.indexOf("?>", i + 2);
    if (end < 0) {
      return err("unclosed <? … ?>", i);
    }
    i = end + 2;
    return true;
  }

  function skipComment() {
    if (s.slice(i, i + 4) !== "<!--") {
      return false;
    }
    var end = s.indexOf("-->", i + 4);
    if (end < 0) {
      return err("unclosed <!--", i);
    }
    i = end + 3;
    return true;
  }

  function skipDoctype() {
    if (s.slice(i, i + 9) !== "<!DOCTYPE") {
      return false;
    }
    var end = s.indexOf(">", i);
    if (end < 0) {
      return err("unclosed <!DOCTYPE", i);
    }
    i = end + 1;
    return true;
  }

  function skipAttributeValue() {
    var q = s.charAt(i);
    if (q !== '"' && q !== "'") {
      return err("attribute value must be quoted", i);
    }
    i++;
    while (i < len && s.charAt(i) !== q) {
      i++;
    }
    if (i >= len) {
      return err("unclosed attribute quote", i);
    }
    i++;
    return null;
  }

  while (i < len) {
    skipSpace();
    if (i >= len) {
      break;
    }
    if (s.charAt(i) !== "<") {
      if (stack.length === 0) {
        return err("unexpected text outside root element", i);
      }
      while (i < len && s.charAt(i) !== "<") {
        i++;
      }
      continue;
    }
    if (s.slice(i, i + 2) === "<?") {
      var pi = skipDeclOrPI();
      if (pi !== true) {
        return pi;
      }
      continue;
    }
    if (s.slice(i, i + 4) === "<!--") {
      var com = skipComment();
      if (com !== true) {
        return com;
      }
      continue;
    }
    if (s.slice(i, i + 9) === "<!DOCTYPE") {
      var dt = skipDoctype();
      if (dt !== true) {
        return dt;
      }
      continue;
    }

    if (s.slice(i, i + 2) === "</") {
      i += 2;
      var closeName = readName();
      if (!closeName) {
        return err("bad closing tag", i);
      }
      skipSpace();
      if (s.charAt(i) !== ">") {
        return err("expected > on closing tag", i);
      }
      i++;
      if (stack.length === 0) {
        return err("unexpected closing tag </" + closeName + ">", i);
      }
      var open = stack.pop();
      if (open !== closeName) {
        return err("tag mismatch: expected </" + open + ">, got </" + closeName + ">", i);
      }
      continue;
    }

    i++;
    var name = readName();
    if (!name) {
      return err("bad element name", i);
    }
    skipSpace();
    while (i < len && s.charAt(i) !== ">" && s.charAt(i) !== "/") {
      var attrName = readName();
      if (!attrName) {
        return err("bad attribute", i);
      }
      skipSpace();
      if (s.charAt(i) !== "=") {
        return err("expected = after attribute " + attrName, i);
      }
      i++;
      skipSpace();
      var av = skipAttributeValue();
      if (av) {
        return av;
      }
      skipSpace();
    }
    var selfClose = false;
    if (s.charAt(i) === "/") {
      selfClose = true;
      i++;
    }
    if (s.charAt(i) !== ">") {
      return err("expected > on opening tag", i);
    }
    i++;
    if (!selfClose) {
      stack.push(name);
    }
  }

  if (stack.length > 0) {
    return err("unclosed element <" + stack[stack.length - 1] + ">", i);
  }
  return { ok: true, file: fileLabel };
}

function checkDocumentSemantics(docXml, fileLabel) {
  fileLabel = fileLabel || "word/document.xml";
  if (docXml.indexOf("<w:document") < 0) {
    return {
      ok: false,
      code: "document_root",
      file: fileLabel,
      message: "missing w:document root",
    };
  }
  if (docXml.indexOf("<w:body") < 0 || docXml.indexOf("</w:body>") < 0) {
    return {
      ok: false,
      code: "document_body",
      file: fileLabel,
      message: "missing w:body",
    };
  }
  return { ok: true };
}

/**
 * Validate a unpacked directory or .docx path.
 *
 * levels (default all): zip | package | xml | document
 * - zip: PK header when validating a .docx file
 * - package: required OPC parts exist
 * - xml: all .xml/.rels parts are well-formed
 * - document: word/document.xml has w:document + w:body
 */
export function validateDocx(options) {
  options = options || {};
  var levels = options.levels || ["zip", "package", "xml", "document"];
  var errors = [];
  var warnings = [];
  var checked = [];
  var tempDir = null;
  var dir = options.dir;
  var docxPath = options.path;

  if (!dir && !docxPath) {
    throw new Error("bad argument: validateDocx: path or dir required");
  }

  if (docxPath && !dir) {
    if (levels.indexOf("zip") >= 0) {
      checked.push("zip");
      if (!isPkZipFile(docxPath)) {
        errors.push({ code: "zip", message: "not a PK zip file", path: docxPath });
      }
    }
    tempDir = options.workDir || "tmp/docx-validate-" + Date.now();
    unpackDocx(docxPath, { workDir: tempDir });
    dir = tempDir;
  }

  if (levels.indexOf("package") >= 0) {
    checked.push("package");
    for (var r = 0; r < DOCX_REQUIRED_PARTS.length; r++) {
      var req = dir + "/" + DOCX_REQUIRED_PARTS[r];
      if (!pathExists(req)) {
        errors.push({
          code: "package_missing",
          message: "missing required part",
          file: DOCX_REQUIRED_PARTS[r],
        });
      }
    }
    for (var w = 0; w < DOCX_RECOMMENDED_PARTS.length; w++) {
      var rec = dir + "/" + DOCX_RECOMMENDED_PARTS[w];
      if (!pathExists(rec)) {
        warnings.push({
          code: "package_recommended",
          message: "missing recommended part",
          file: DOCX_RECOMMENDED_PARTS[w],
        });
      }
    }
  }

  var xmlPaths = [];
  if (levels.indexOf("xml") >= 0 || levels.indexOf("document") >= 0) {
    xmlPaths = collectXmlPartPaths(dir);
  }

  if (levels.indexOf("xml") >= 0) {
    checked.push("xml");
    if (xmlPaths.length === 0) {
      warnings.push({ code: "xml_none", message: "no XML parts discovered (limited fs list?)" });
    }
    for (var xi = 0; xi < xmlPaths.length; xi++) {
      var rel = xmlPaths[xi];
      var label = rel.indexOf(dir + "/") === 0 ? rel.slice(dir.length + 1) : rel;
      var text;
      try {
        text = readText(rel);
      } catch (e) {
        errors.push({ code: "xml_read", file: label, message: String(e.message || e) });
        continue;
      }
      var wf = checkXmlWellFormed(text, label);
      if (!wf.ok) {
        errors.push({
          code: "xml_malformed",
          file: wf.file,
          message: wf.message,
          line: wf.line,
          column: wf.column,
        });
      }
    }
  }

  if (levels.indexOf("document") >= 0) {
    checked.push("document");
    var docPath = dir + "/word/document.xml";
    if (pathExists(docPath)) {
      var docXml = readText(docPath);
      var sem = checkDocumentSemantics(docXml, "word/document.xml");
      if (!sem.ok) {
        errors.push(sem);
      }
    }
  }

  return {
    ok: errors.length === 0,
    errors: errors,
    warnings: warnings,
    checked: checked,
    dir: dir,
    tempUnpack: tempDir,
  };
}

export default {
  unpackDocx: unpackDocx,
  packDocx: packDocx,
  validateDocx: validateDocx,
  checkXmlWellFormed: checkXmlWellFormed,
};
