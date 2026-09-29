/**
 * docx-build.js — fluent Builder / block DSL on top of docx.js (authoring only).
 *
 * import { buildDoc, buildDocx, DocBuilder } from "./docx-build.js";
 */
import { Document, renderDocx } from "./docx.js";

function DocBuilder(doc) {
  this._doc = doc;
  this._runStyle = null;
}

DocBuilder.prototype._style = function (style) {
  var merged = {};
  if (this._runStyle) {
    Object.assign(merged, this._runStyle);
  }
  if (style) {
    Object.assign(merged, style);
  }
  return Object.keys(merged).length ? merged : undefined;
};

/** Document metadata (core.xml title uses doc.title). */
DocBuilder.prototype.title = function (text) {
  this._doc.title = String(text);
  return this;
};

/** Default style for following blocks (clears per-run `.style()`). */
DocBuilder.prototype.defaultStyle = function (style) {
  this._doc.setDefaultStyle(style || {});
  this._runStyle = null;
  return this;
};

/** Style for the next text block only (chain before `.p()` / `.bullet()` etc.). */
DocBuilder.prototype.style = function (style) {
  this._runStyle = Object.assign({}, this._runStyle, style || {});
  return this;
};

DocBuilder.prototype.h = function (text, level) {
  this._doc.addHeading(String(text), level == null ? 1 : level);
  this._runStyle = null;
  return this;
};

DocBuilder.prototype.h1 = function (text) {
  return this.h(text, 1);
};
DocBuilder.prototype.h2 = function (text) {
  return this.h(text, 2);
};
DocBuilder.prototype.h3 = function (text) {
  return this.h(text, 3);
};

DocBuilder.prototype.p = function (text, style) {
  this._doc.addParagraph(String(text), this._style(style));
  this._runStyle = null;
  return this;
};

DocBuilder.prototype.bullet = function (text, style) {
  this._doc.addBullet(String(text), this._style(style));
  this._runStyle = null;
  return this;
};

DocBuilder.prototype.task = function (text, checked, style) {
  this._doc.addTask(String(text), !!checked, this._style(style));
  this._runStyle = null;
  return this;
};

DocBuilder.prototype.quote = function (text) {
  this._doc.addBlockquote(String(text));
  this._runStyle = null;
  return this;
};

DocBuilder.prototype.code = function (text) {
  this._doc.addCode(String(text));
  this._runStyle = null;
  return this;
};

/** `.table([["A","B"],["1","2"]])` or `.table(["A","B"], ["1","2"])`. */
DocBuilder.prototype.table = function (rows) {
  var list;
  if (rows && rows.length && Object.prototype.toString.call(rows[0]) !== "[object Array]") {
    list = [];
    for (var i = 0; i < arguments.length; i++) {
      list.push(arguments[i]);
    }
  } else {
    list = rows || [];
  }
  this._doc.addTable(list);
  this._runStyle = null;
  return this;
};

DocBuilder.prototype.image = function (workspacePath, width, height) {
  this._doc.addImage(workspacePath, width, height);
  this._runStyle = null;
  return this;
};

/**
 * Declarative block (JSON-friendly for Agent tools):
 * { type: "h1"|"h2"|"p"|"bullet"|"task"|"quote"|"code"|"table"|"image", text, level, checked, rows, path, style }
 */
DocBuilder.prototype.block = function (spec) {
  spec = spec || {};
  var t = spec.type || spec.t || "p";
  var st = this._style(spec.style);
  if (t === "h1" || t === "heading") {
    this.h(spec.text, spec.level == null ? 1 : spec.level);
  } else if (t === "h2") {
    this.h(spec.text, 2);
  } else if (t === "h3") {
    this.h(spec.text, 3);
  } else if (t === "p" || t === "paragraph") {
    this.p(spec.text, st);
  } else if (t === "bullet") {
    this.bullet(spec.text, st);
  } else if (t === "task") {
    this.task(spec.text, spec.checked, st);
  } else if (t === "quote" || t === "blockquote") {
    this.quote(spec.text);
  } else if (t === "code") {
    this.code(spec.text);
  } else if (t === "table") {
    this.table(spec.rows || []);
  } else if (t === "image") {
    this.image(spec.path, spec.width, spec.height);
  } else {
    throw new Error("docx-build: unknown block type " + t);
  }
  return this;
};

DocBuilder.prototype.blocks = function (list) {
  for (var i = 0; i < (list || []).length; i++) {
    this.block(list[i]);
  }
  return this;
};

DocBuilder.prototype.doc = function () {
  return this._doc;
};

DocBuilder.prototype.save = function (outputPath) {
  return this._doc.save(outputPath);
};

/**
 * Create a document with a fluent callback.
 * buildDoc({ defaultStyle: {...} }, (b) => b.h1("T").p("body"));
 */
export function buildDoc(options, fn) {
  var doc = Document.create(options || {});
  var b = new DocBuilder(doc);
  if (typeof fn === "function") {
    fn(b);
  }
  return doc;
}

/** buildDoc + optional render in one call. */
export function buildDocx(options, fn, outputPath) {
  var doc = buildDoc(options, fn);
  if (!outputPath) {
    return doc;
  }
  return renderDocx(doc, outputPath);
}

Document.builder = function (options) {
  return new DocBuilder(Document.create(options || {}));
};

export { DocBuilder };

export default {
  buildDoc: buildDoc,
  buildDocx: buildDocx,
  DocBuilder: DocBuilder,
};
