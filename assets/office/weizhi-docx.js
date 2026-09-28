/**
 * Weizhi docx helper — semantic edit API for AI scripts (QuickJS).
 * Depends: fs, path, require("zip"). Optional: host.office.docx.fromMarkdown for create.
 *
 * Usage (script folder set via setScriptFolder):
 *   import WeizhiDocx from "./weizhi-docx.js";
 *   const doc = WeizhiDocx.open("out/report.docx");
 *   doc.setTitle("新标题");
 *   doc.setBodyStyle({ font: "宋体", sizePt: 12, lineSpacing: 1.5 });
 *   doc.save("out/report-edited.docx");
 */
var zip = require("zip");
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

  /** Line spacing multiplier → w:line in 240ths of a line (auto rule). */
  function lineSpacingToW(lineSpacing) {
    var mult = Number(lineSpacing);
    if (!(mult > 0)) {
      mult = 1.15;
    }
    return Math.round(240 * mult);
  }

  function joinPath(a, b) {
    if (a.endsWith("/")) {
      return a + b;
    }
    return a + "/" + b;
  }

  /** Prefer caps Java zip (Office-friendly); fall back to engine zip. */
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

  function pack(sourceDir, outDocx) {
    var files = platformFiles();
    if (files && files.zipCreate) {
      files.zipCreate(sourceDir, outDocx);
      return;
    }
    zip.createSync(sourceDir, outDocx);
  }

  function readUtf8(path) {
    return fs.readFileSync(path).toString();
  }

  function writeUtf8(path, text) {
    fs.writeFileSync(path, text);
  }

  function extractTextFromParagraph(inner) {
    var texts = [];
    var re = /<w:t(?:\s+xml:space="preserve")?>([\s\S]*?)<\/w:t>/g;
    var m;
    while ((m = re.exec(inner)) !== null) {
      texts.push(
        m[1]
          .replace(/&lt;/g, "<")
          .replace(/&gt;/g, ">")
          .replace(/&quot;/g, '"')
          .replace(/&amp;/g, "&")
      );
    }
    return texts.join("");
  }

  function getParagraphStyle(inner) {
    var m = inner.match(/<w:pStyle\s+w:val="([^"]+)"/);
    return m ? m[1] : null;
  }

  function parseParagraphs(documentXml) {
    var bodyMatch = documentXml.match(/<w:body>([\s\S]*?)<w:sectPr/);
    if (!bodyMatch) {
      throw new Error("bad docx: missing w:body");
    }
    var bodyInner = bodyMatch[1];
    var parts = [];
    var re = /<w:p[\s\S]*?<\/w:p>/g;
    var m;
    while ((m = re.exec(bodyInner)) !== null) {
      var block = m[0];
      var inner = block.replace(/^<w:p[^>]*>/, "").replace(/<\/w:p>$/, "");
      parts.push({
        raw: block,
        inner: inner,
        text: extractTextFromParagraph(inner),
        style: getParagraphStyle(inner),
      });
    }
    return parts;
  }

  function stripPPr(inner) {
    return inner.replace(/<w:pPr>[\s\S]*?<\/w:pPr>/, "");
  }

  function stripRPr(inner) {
    return inner.replace(/<w:rPr>[\s\S]*?<\/w:rPr>/g, "");
  }

  function buildRun(text, runPropsXml) {
    var rPr = runPropsXml ? "<w:rPr>" + runPropsXml + "</w:rPr>" : "";
    var t = xmlEscape(text);
    var space = text.indexOf(" ") >= 0 || text.indexOf("\t") >= 0 ? ' xml:space="preserve"' : "";
    return "<w:r>" + rPr + "<w:t" + space + ">" + t + "</w:t></w:r>";
  }

  function buildParagraph(text, opts) {
    opts = opts || {};
    var pPrParts = [];
    if (opts.style) {
      pPrParts.push('<w:pStyle w:val="' + xmlEscape(opts.style) + '"/>');
    }
    if (opts.spacing) {
      var sp = opts.spacing;
      var attrs = "";
      if (sp.before != null) {
        attrs += ' w:before="' + Math.round(Number(sp.before)) + '"';
      }
      if (sp.after != null) {
        attrs += ' w:after="' + Math.round(Number(sp.after)) + '"';
      }
      if (sp.line != null) {
        attrs += ' w:line="' + Math.round(Number(sp.line)) + '" w:lineRule="auto"';
      }
      if (attrs) {
        pPrParts.push("<w:spacing" + attrs + "/>");
      }
    }
    if (opts.jc) {
      pPrParts.push('<w:jc w:val="' + xmlEscape(opts.jc) + '"/>');
    }
    var pPr = pPrParts.length ? "<w:pPr>" + pPrParts.join("") + "</w:pPr>" : "";
    var runProps = opts.runPropsXml || "";
    var run;
    if (opts.bullet) {
      run = buildRun("• " + text, runProps);
    } else {
      run = buildRun(text, runProps);
    }
    return "<w:p>" + pPr + run + "</w:p>";
  }

  function serializeDocument(paragraphs, sectPr) {
    if (!sectPr) {
      sectPr =
        '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
        + '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>';
    }
    var body = paragraphs.map(function (p) {
      if (p._built) {
        return p._built;
      }
      return p.raw;
    }).join("");
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<w:document xmlns:w="' + W_NS + '"><w:body>'
      + body + sectPr + "</w:body></w:document>";
  }

  function DocxDocument(workDir, documentPath) {
    this.workDir = workDir;
    this.documentPath = documentPath;
    this._sectPr = null;
    this._paragraphs = [];
    this._defaultRunProps = "";
    this._defaultSpacing = null;
    this._titleStyleName = "Title";
    this._bodyStyleName = "Normal";
    this._reload();
  }

  DocxDocument.prototype._reload = function () {
    var xml = readUtf8(this.documentPath);
    var sect = xml.match(/<w:sectPr[\s\S]*?<\/w:sectPr>/);
    this._sectPr = sect ? sect[0] : null;
    this._paragraphs = parseParagraphs(xml);
  };

  DocxDocument.prototype.getPlainText = function () {
    return this._paragraphs.map(function (p) { return p.text; }).join("\n");
  };

  DocxDocument.prototype.getTitle = function () {
    for (var i = 0; i < this._paragraphs.length; i++) {
      var p = this._paragraphs[i];
      if (p.style === this._titleStyleName || p.style === "Heading1") {
        return p.text;
      }
    }
    return this._paragraphs.length ? this._paragraphs[0].text : "";
  };

  DocxDocument.prototype.setTitle = function (text) {
    text = String(text);
    var found = false;
    for (var i = 0; i < this._paragraphs.length; i++) {
      var p = this._paragraphs[i];
      if (p.style === this._titleStyleName || p.style === "Heading1" || (!found && i === 0)) {
        p._built = buildParagraph(text, {
          style: this._titleStyleName,
          runPropsXml: this._defaultRunProps,
          spacing: this._defaultSpacing,
        });
        p.text = text;
        found = true;
        if (p.style === this._titleStyleName || p.style === "Heading1") {
          break;
        }
      }
    }
    if (!found) {
      this._paragraphs.unshift({
        text: text,
        _built: buildParagraph(text, {
          style: this._titleStyleName,
          runPropsXml: this._defaultRunProps,
          spacing: this._defaultSpacing,
        }),
      });
    }
    return this;
  };

  DocxDocument.prototype.setBodyStyle = function (opts) {
    opts = opts || {};
    var runParts = [];
    if (opts.font) {
      runParts.push(
        '<w:rFonts w:ascii="' + xmlEscape(opts.font) + '" w:hAnsi="' + xmlEscape(opts.font) + '"/>'
      );
    }
    if (opts.sizePt != null) {
      runParts.push('<w:sz w:val="' + ptToHalfPoints(opts.sizePt) + '"/>');
      runParts.push('<w:szCs w:val="' + ptToHalfPoints(opts.sizePt) + '"/>');
    }
    if (opts.bold) {
      runParts.push("<w:b/>");
    }
    if (opts.italic) {
      runParts.push("<w:i/>");
    }
    if (opts.characterSpacingPt != null) {
      runParts.push(
        '<w:spacing w:val="' + Math.round(Number(opts.characterSpacingPt) * 20) + '"/>'
      );
    }
    this._defaultRunProps = runParts.join("");
    if (opts.lineSpacing != null) {
      this._defaultSpacing = { line: lineSpacingToW(opts.lineSpacing) };
    }
    if (opts.paragraphSpacingBefore != null || opts.paragraphSpacingAfter != null) {
      this._defaultSpacing = this._defaultSpacing || {};
      if (opts.paragraphSpacingBefore != null) {
        this._defaultSpacing.before = Number(opts.paragraphSpacingBefore);
      }
      if (opts.paragraphSpacingAfter != null) {
        this._defaultSpacing.after = Number(opts.paragraphSpacingAfter);
      }
    }
    for (var i = 0; i < this._paragraphs.length; i++) {
      var p = this._paragraphs[i];
      if (p.style === this._titleStyleName) {
        continue;
      }
      p._built = buildParagraph(p.text, {
        style: p.style === "Heading1" ? "Heading1" : this._bodyStyleName,
        runPropsXml: this._defaultRunProps,
        spacing: this._defaultSpacing,
        bullet: p.text.indexOf("• ") === 0,
      });
    }
    return this;
  };

  DocxDocument.prototype.addParagraph = function (text, opts) {
    opts = opts || {};
    var built = buildParagraph(String(text), {
      style: opts.style || this._bodyStyleName,
      runPropsXml: this._defaultRunProps,
      spacing: this._defaultSpacing,
      bullet: !!opts.bullet,
      jc: opts.align,
    });
    this._paragraphs.push({ text: String(text), _built: built });
    return this;
  };

  DocxDocument.prototype.replaceParagraph = function (index, text, opts) {
    if (index < 0 || index >= this._paragraphs.length) {
      throw new Error("bad argument: paragraph index " + index);
    }
    opts = opts || {};
    var p = this._paragraphs[index];
    p.text = String(text);
    p._built = buildParagraph(p.text, {
      style: opts.style || p.style || this._bodyStyleName,
      runPropsXml: this._defaultRunProps,
      spacing: this._defaultSpacing,
      bullet: !!opts.bullet,
    });
    return this;
  };

  DocxDocument.prototype.save = function (outputDocxPath) {
    var xml = serializeDocument(this._paragraphs, this._sectPr);
    writeUtf8(this.documentPath, xml);
    pack(this.workDir, outputDocxPath);
    return { ok: true, path: outputDocxPath };
  };

  DocxDocument.open = function (docxPath, options) {
    options = options || {};
    var workDir = options.workDir;
    if (!workDir) {
      var base = docxPath.replace(/\.docx$/i, "");
      workDir = "tmp/weizhi-docx-" + base.replace(/\//g, "_") + "-" + Date.now();
    }
    unpack(docxPath, workDir);
    var docPath = joinPath(workDir, "word/document.xml");
    if (!fs.existsSync(docPath)) {
      throw new Error("bad docx: word/document.xml missing after unpack");
    }
    return new DocxDocument(workDir, docPath);
  };

export { DocxDocument };
export default {
  DocxDocument: DocxDocument,
  open: function (path, opts) {
    return DocxDocument.open(path, opts);
  },
};
