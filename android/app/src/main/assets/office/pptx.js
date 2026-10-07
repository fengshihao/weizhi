/**
 * pptx.js — Deck model and OOXML render (layouts, theme chrome, semantic shapes).
 *
 * import { renderPptx, createDeck } from "./pptx.js";
 *
 * Coordinates stay inside the renderer. Callers pick a layout, a theme, and
 * theme color names — not EMU positions or DrawingML.
 */
var fs = require("fs");
var zip = require("zip");

var P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main";
var A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
var R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
var REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
var CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types";

var SLIDE_CX = 12192000;
var SLIDE_CY = 6858000;
var MAX_SLIDES = 40;
var MAX_SHAPES = 6;
var MAX_ITEMS = 8;

var LAYOUTS = {
  title: 1,
  section: 1,
  bullets: 1,
  twoColumn: 1,
  image: 1,
  table: 1,
  stat: 1,
  steps: 1,
  callout: 1,
  cards: 1,
  shapes: 1,
};

var PRESETS = {
  rect: 1,
  roundRect: 1,
  ellipse: 1,
  chevron: 1,
  rightArrow: 1,
};

var FILL_KEYS = {
  bg: 1,
  bg2: 1,
  accent: 1,
  accent2: 1,
  surface: 1,
  text: 1,
  muted: 1,
  onAccent: 1,
};

var THEMES = {
  briefing: {
    name: "briefing",
    bg: "F7F5F2",
    bg2: "E7EEF5",
    accent: "1F4E79",
    accent2: "C4A35A",
    surface: "FFFFFF",
    text: "1A1A1A",
    muted: "5C6570",
    onAccent: "FFFFFF",
    gradient: false,
  },
  dark: {
    name: "dark",
    bg: "1B2430",
    bg2: "243044",
    accent: "3DDC97",
    accent2: "F2C14E",
    surface: "2C3A4F",
    text: "F4F7FA",
    muted: "A8B3C0",
    onAccent: "102018",
    gradient: true,
  },
};

function xmlEscape(s) {
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
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

function packDir(sourceDir, outPath) {
  var files = platformFiles();
  if (files && files.zipCreate) {
    files.zipCreate(sourceDir, outPath);
    return;
  }
  zip.createSync(sourceDir, outPath);
}

function mkdirp(relDir) {
  var files = platformFiles();
  if (!files || !files.mkdir) {
    throw new Error("bad argument: renderPptx: platform files.mkdir required (install caps)");
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

function isArray(v) {
  return Object.prototype.toString.call(v) === "[object Array]";
}

function asList(v) {
  if (v == null) {
    return [];
  }
  if (isArray(v)) {
    return v;
  }
  return [String(v)];
}

function normalizeLayout(layout) {
  if (layout === "two-column" || layout === "two_column") {
    return "twoColumn";
  }
  if (layout === "bullet") {
    return "bullets";
  }
  if (layout === "blank") {
    return "shapes";
  }
  return layout;
}

function themeByName(name) {
  var key = name || "briefing";
  if (!THEMES[key]) {
    throw new Error("bad argument: renderPptx: unknown theme");
  }
  return THEMES[key];
}

function fillHex(theme, key) {
  if (!key) {
    key = "surface";
  }
  if (!FILL_KEYS[key]) {
    throw new Error("bad argument: renderPptx: unknown fill");
  }
  return theme[key];
}

function textOn(theme, fillKey) {
  if (fillKey === "accent") {
    return theme.onAccent;
  }
  if (fillKey === "accent2" || fillKey === "onAccent") {
    return "1A1A1A";
  }
  if (fillKey === "muted") {
    return "FFFFFF";
  }
  return theme.text;
}

function imageExt(path) {
  var m = String(path).match(/\.([A-Za-z0-9]+)$/);
  var ext = m ? m[1].toLowerCase() : "";
  if (ext === "jpg" || ext === "jpeg") {
    return "jpeg";
  }
  if (ext === "png" || ext === "gif") {
    return ext;
  }
  throw new Error("bad argument: renderPptx: image type");
}

function limitItems(items, what) {
  if (items.length > MAX_ITEMS) {
    throw new Error("bad argument: renderPptx: too many " + what);
  }
  return items;
}

function statItem(it) {
  if (typeof it === "string" || typeof it === "number") {
    return { value: String(it), label: "" };
  }
  it = it || {};
  return {
    value: it.value != null ? String(it.value) : "",
    label: it.label != null ? String(it.label) : it.text != null ? String(it.text) : "",
  };
}

function cardItem(it) {
  if (typeof it === "string" || typeof it === "number") {
    return { title: String(it), text: "" };
  }
  it = it || {};
  return {
    title: it.title != null ? String(it.title) : it.value != null ? String(it.value) : "",
    text: it.text != null ? String(it.text) : it.label != null ? String(it.label) : "",
  };
}

function resolveBackground(slide, theme) {
  var bg = slide.background;
  if ((bg == null || bg === "") && slide.layout === "section" && theme.name === "briefing") {
    return { kind: "accent" };
  }
  if (bg == null || bg === "") {
    return { kind: "theme" };
  }
  if (typeof bg === "string") {
    if (bg === "dark" || bg === "light" || bg === "accent") {
      return { kind: bg };
    }
    throw new Error("bad argument: renderPptx: unknown background");
  }
  if (bg.image) {
    return { kind: "image", image: String(bg.image) };
  }
  if (bg.color) {
    if (!FILL_KEYS[bg.color]) {
      throw new Error("bad argument: renderPptx: unknown fill");
    }
    return { kind: "color", colorKey: bg.color };
  }
  throw new Error("bad argument: renderPptx: unknown background");
}

function slideInk(theme, bg) {
  if (bg.kind === "accent" || (bg.kind === "color" && bg.colorKey === "accent")) {
    return { text: theme.onAccent, muted: "E6EEF6" };
  }
  if (bg.kind === "dark" || bg.kind === "image") {
    return { text: "F4F7FA", muted: "C5D0DC" };
  }
  if (bg.kind === "light") {
    return { text: "1A1A1A", muted: "5C6570" };
  }
  if (bg.kind === "color") {
    var c = textOn(theme, bg.colorKey);
    return { text: c, muted: c };
  }
  return { text: theme.text, muted: theme.muted };
}

function metricsFor(hasTitle) {
  var left = 594360;
  var right = 457200;
  var width = SLIDE_CX - left - right;
  var contentTop = hasTitle ? 868680 : 365760;
  var contentBottom = 6492240;
  return {
    left: left,
    width: width,
    contentTop: contentTop,
    contentBottom: contentBottom,
    height: contentBottom - contentTop,
  };
}

function gridBox(m, col, row, colSpan, rowSpan) {
  col = col == null ? 0 : Number(col);
  row = row == null ? 0 : Number(row);
  colSpan = colSpan == null ? 1 : Number(colSpan);
  rowSpan = rowSpan == null ? 1 : Number(rowSpan);
  if (
    isNaN(col) ||
    isNaN(row) ||
    isNaN(colSpan) ||
    isNaN(rowSpan) ||
    col !== Math.floor(col) ||
    row !== Math.floor(row) ||
    colSpan !== Math.floor(colSpan) ||
    rowSpan !== Math.floor(rowSpan) ||
    col < 0 ||
    row < 0 ||
    colSpan < 1 ||
    rowSpan < 1 ||
    col + colSpan > 12 ||
    row + rowSpan > 6
  ) {
    throw new Error("bad argument: renderPptx: shape out of grid");
  }
  var gx = 100584;
  var gy = 91440;
  var colW = Math.floor((m.width - 11 * gx) / 12);
  var rowH = Math.floor((m.height - 5 * gy) / 6);
  return {
    x: m.left + col * (colW + gx),
    y: m.contentTop + row * (rowH + gy),
    cx: colSpan * colW + (colSpan - 1) * gx,
    cy: rowSpan * rowH + (rowSpan - 1) * gy,
  };
}

function validateSlide(slide) {
  var layout = normalizeLayout(slide.layout);
  if (!LAYOUTS[layout]) {
    throw new Error("bad argument: renderPptx: unknown layout");
  }
  slide.layout = layout;
  if (layout === "shapes") {
    var shapes = slide.shapes || [];
    if (!isArray(shapes)) {
      throw new Error("bad argument: renderPptx: shapes required");
    }
    if (shapes.length > MAX_SHAPES) {
      throw new Error("bad argument: renderPptx: too many shapes");
    }
    var m = metricsFor(!!(slide.title && String(slide.title).length));
    for (var i = 0; i < shapes.length; i++) {
      var sh = shapes[i] || {};
      var preset = sh.preset || "roundRect";
      if (!PRESETS[preset]) {
        throw new Error("bad argument: renderPptx: unknown preset");
      }
      if (sh.fill && !FILL_KEYS[sh.fill]) {
        throw new Error("bad argument: renderPptx: unknown fill");
      }
      gridBox(m, sh.col, sh.row, sh.colSpan, sh.rowSpan);
    }
  }
  if (layout === "image" && !slide.image) {
    throw new Error("bad argument: renderPptx: image required");
  }
  if (layout === "table") {
    var rows = slide.rows || [];
    if (!isArray(rows) || rows.length === 0) {
      throw new Error("bad argument: renderPptx: table rows required");
    }
  }
  if (layout === "bullets" || layout === "steps") {
    limitItems(asList(slide.items), "items");
  }
  if (layout === "stat" || layout === "cards") {
    var list = slide.items || [];
    if (!isArray(list)) {
      list = asList(list);
    }
    limitItems(list, "items");
  }
  if (layout === "twoColumn") {
    limitItems(asList(slide.left), "items");
    limitItems(asList(slide.right), "items");
  }
}

function addShape(list, x, y, cx, cy, preset, fill, alpha) {
  list.push({
    kind: "sp",
    x: x,
    y: y,
    cx: cx,
    cy: cy,
    preset: preset,
    fill: fill,
    alpha: alpha || null,
    paragraphs: null,
  });
}

function addText(list, x, y, cx, cy, paragraphs, opts) {
  opts = opts || {};
  if (typeof paragraphs === "string" || typeof paragraphs === "number") {
    paragraphs = [
      {
        text: String(paragraphs),
        sz: opts.sz || 1800,
        bold: !!opts.bold,
        color: opts.color || "1A1A1A",
        align: opts.align || "l",
      },
    ];
  }
  list.push({
    kind: "sp",
    x: x,
    y: y,
    cx: cx,
    cy: cy,
    preset: opts.preset || "rect",
    fill: opts.fill || null,
    alpha: opts.alpha || null,
    paragraphs: paragraphs,
    anchor: opts.anchor || "ctr",
    lIns: opts.lIns != null ? opts.lIns : 91440,
    tIns: opts.tIns != null ? opts.tIns : 45720,
    rIns: opts.rIns != null ? opts.rIns : 91440,
    bIns: opts.bIns != null ? opts.bIns : 45720,
  });
}

function listParagraphs(items, color, sz) {
  var ps = [];
  for (var i = 0; i < items.length; i++) {
    ps.push({
      text: "•  " + String(items[i]),
      sz: sz,
      bold: false,
      color: color,
      align: "l",
    });
  }
  return ps;
}

function addChrome(list, slide, theme, bg) {
  if (bg.kind === "image") {
    addShape(list, 0, 0, SLIDE_CX, SLIDE_CY, "rect", "1B2430", 48000);
  }
  if (slide.layout === "section" && theme.name === "dark") {
    addShape(list, 0, 0, 274320, SLIDE_CY, "rect", theme.accent, null);
    return;
  }
  if (slide.layout === "section" && bg.kind === "accent") {
    addShape(list, 0, SLIDE_CY - 100584, SLIDE_CX, 100584, "rect", theme.accent2, null);
    return;
  }
  if (slide.layout === "title" && theme.name === "briefing" && bg.kind !== "image" && bg.kind !== "accent") {
    addShape(list, 0, 0, 274320, SLIDE_CY, "rect", theme.accent, null);
    addShape(list, SLIDE_CX - 640080, -548640, 2286000, 2286000, "ellipse", theme.accent2, 18000);
    return;
  }
  if (theme.name === "briefing" && bg.kind !== "accent" && bg.kind !== "image" && bg.kind !== "dark") {
    addShape(list, 0, 0, 137160, SLIDE_CY, "rect", theme.accent, null);
  }
  if ((theme.name === "dark" || bg.kind === "dark") && bg.kind !== "image") {
    addShape(list, 0, 0, SLIDE_CX, 100584, "rect", theme.accent, null);
    if (slide.layout === "title") {
      addShape(list, SLIDE_CX - 731520, -365760, 2011680, 2011680, "ellipse", theme.accent, 20000);
    }
  }
}

function addTitleBlock(list, slide, theme, ink, m) {
  if (!slide.title) {
    return;
  }
  addText(list, m.left, 182880, m.width, 502920, String(slide.title), {
    sz: 2800,
    bold: true,
    color: ink.text,
    align: "l",
    anchor: "b",
    lIns: 0,
    rIns: 0,
    bIns: 0,
  });
  addShape(list, m.left, 731520, 1280160, 36576, "rect", theme.accent, null);
}

function addFooter(list, index, total, m, ink) {
  addText(list, m.left, 6537960, m.width, 228600, index + 1 + "  /  " + total, {
    sz: 1200,
    color: ink.muted,
    align: "r",
    anchor: "ctr",
    lIns: 0,
    rIns: 0,
  });
}

function renderTitle(list, slide, ink) {
  var x = 640080;
  var cx = SLIDE_CX - x - 640080;
  var title = slide.title ? String(slide.title) : "";
  var sz = title.length > 16 ? 3200 : 4400;
  addText(list, x, 1645920, cx, 1371600, title, {
    sz: sz,
    bold: true,
    color: ink.text,
    align: "l",
    anchor: "b",
    lIns: 0,
    rIns: 0,
  });
  if (slide.subtitle) {
    addText(list, x, 3200400, cx, 640080, String(slide.subtitle), {
      sz: 2000,
      color: ink.muted,
      align: "l",
      anchor: "t",
      lIns: 0,
      rIns: 0,
    });
  }
}

function renderSection(list, slide, theme, ink) {
  var x = theme.name === "dark" ? 548640 : 640080;
  var cx = SLIDE_CX - x - 548640;
  addText(list, x, 2000000, cx, 1371600, slide.title ? String(slide.title) : "", {
    sz: 4000,
    bold: true,
    color: ink.text,
    align: theme.name === "dark" ? "l" : "l",
    anchor: "b",
    lIns: 0,
  });
  if (slide.subtitle) {
    addText(list, x, 3500000, cx, 548640, String(slide.subtitle), {
      sz: 2000,
      color: ink.muted,
      align: "l",
      anchor: "t",
      lIns: 0,
    });
  }
}

function renderBullets(list, slide, theme, m) {
  var items = limitItems(asList(slide.items), "items");
  addShape(list, m.left, m.contentTop, m.width, m.height, "roundRect", theme.surface, null);
  if (items.length === 0) {
    return;
  }
  var pad = 160020;
  var gap = 54864;
  var innerH = m.height - pad * 2;
  var rowH = Math.floor((innerH - (items.length - 1) * gap) / items.length);
  var color = textOn(theme, "surface");
  var sz = items.length > 6 ? 1600 : 2000;
  for (var i = 0; i < items.length; i++) {
    var y = m.contentTop + pad + i * (rowH + gap);
    var marker = Math.min(128016, Math.max(64008, rowH - 45720));
    var my = y + Math.floor((rowH - marker) / 2);
    addShape(list, m.left + pad, my, marker, marker, "ellipse", theme.accent, null);
    addText(
      list,
      m.left + pad + marker + 91440,
      y,
      m.width - pad * 2 - marker - 91440,
      rowH,
      String(items[i]),
      { sz: sz, color: color, align: "l", anchor: "ctr", lIns: 22860, rIns: 22860 }
    );
  }
}

function renderTwoColumn(list, slide, theme, m) {
  var left = limitItems(asList(slide.left), "items");
  var right = limitItems(asList(slide.right), "items");
  var gap = 182880;
  var colW = Math.floor((m.width - gap) / 2);
  var color = textOn(theme, "surface");
  var heads = [slide.leftTitle || "", slide.rightTitle || ""];
  var cols = [left, right];
  for (var i = 0; i < 2; i++) {
    var x = m.left + i * (colW + gap);
    addShape(list, x, m.contentTop, colW, m.height, "roundRect", theme.surface, null);
    var ps = [];
    if (heads[i]) {
      ps.push({ text: String(heads[i]), sz: 2000, bold: true, color: theme.accent, align: "l" });
    }
    var body = listParagraphs(cols[i], color, 1800);
    for (var j = 0; j < body.length; j++) {
      ps.push(body[j]);
    }
    addText(list, x + 91440, m.contentTop + 91440, colW - 182880, m.height - 182880, ps, {
      anchor: "t",
      lIns: 45720,
      tIns: 45720,
    });
  }
}

function renderStat(list, slide, theme, m) {
  var raw = isArray(slide.items) ? slide.items : asList(slide.items);
  var items = limitItems(raw, "items").map(statItem);
  if (items.length === 0) {
    return;
  }
  var cols = items.length <= 4 ? items.length : Math.ceil(items.length / 2);
  var rows = items.length <= 4 ? 1 : 2;
  var gap = 137160;
  var cardW = Math.floor((m.width - (cols - 1) * gap) / cols);
  var cardH = Math.floor((m.height - (rows - 1) * gap) / rows);
  for (var i = 0; i < items.length; i++) {
    var c = i % cols;
    var r = Math.floor(i / cols);
    var x = m.left + c * (cardW + gap);
    var y = m.contentTop + r * (cardH + gap);
    addShape(list, x, y, cardW, cardH, "roundRect", theme.surface, null);
    var ps = [
      { text: items[i].value, sz: rows > 1 ? 2800 : 3600, bold: true, color: theme.accent, align: "ctr" },
    ];
    if (items[i].label) {
      ps.push({ text: items[i].label, sz: 1400, bold: false, color: theme.muted, align: "ctr" });
    }
    addText(list, x + 91440, y + 91440, cardW - 182880, cardH - 182880, ps, { anchor: "ctr", lIns: 45720 });
  }
}

function renderSteps(list, slide, theme, m) {
  var items = limitItems(asList(slide.items), "items");
  if (items.length === 0) {
    return;
  }
  var n = items.length;
  var overlap = 80000;
  var each = Math.floor((m.width + (n - 1) * overlap) / n);
  var h = Math.min(m.height, 1600200);
  var y = m.contentTop + Math.floor((m.height - h) / 2);
  for (var i = 0; i < n; i++) {
    var fillKey = i % 2 === 0 ? "accent" : "accent2";
    var x = m.left + i * (each - overlap);
    addText(list, x, y, each, h, String(items[i]), {
      preset: "chevron",
      fill: theme[fillKey],
      sz: n > 5 ? 1400 : 1800,
      bold: true,
      color: textOn(theme, fillKey),
      align: "ctr",
      anchor: "ctr",
      lIns: 137160,
      rIns: 200000,
    });
  }
}

function renderCallout(list, slide, theme, m) {
  addShape(list, m.left, m.contentTop, m.width, m.height, "roundRect", theme.surface, null);
  addShape(list, m.left, m.contentTop, 137160, m.height, "rect", theme.accent, null);
  var ps = [{
    text: slide.text != null ? String(slide.text) : "",
    sz: 2800,
    bold: false,
    color: textOn(theme, "surface"),
    align: "l",
  }];
  addText(list, m.left + 274320, m.contentTop + 182880, m.width - 457200, m.height - 365760, ps, {
    anchor: "ctr",
    lIns: 45720,
  });
}

function renderCards(list, slide, theme, m) {
  var raw = isArray(slide.items) ? slide.items : asList(slide.items);
  var items = limitItems(raw, "items").map(cardItem);
  if (items.length === 0) {
    return;
  }
  var cols = items.length <= 3 ? items.length : items.length === 4 ? 2 : 3;
  var rows = Math.ceil(items.length / cols);
  var gap = 137160;
  var cardW = Math.floor((m.width - (cols - 1) * gap) / cols);
  var cardH = Math.floor((m.height - (rows - 1) * gap) / rows);
  for (var i = 0; i < items.length; i++) {
    var c = i % cols;
    var r = Math.floor(i / cols);
    var x = m.left + c * (cardW + gap);
    var y = m.contentTop + r * (cardH + gap);
    addShape(list, x, y, cardW, cardH, "roundRect", theme.surface, null);
    var ps = [{ text: items[i].title, sz: 2000, bold: true, color: theme.accent, align: "l" }];
    if (items[i].text) {
      ps.push({ text: items[i].text, sz: 1400, bold: false, color: textOn(theme, "surface"), align: "l" });
    }
    addText(list, x + 137160, y + 137160, cardW - 274320, cardH - 274320, ps, {
      anchor: "t",
      lIns: 22860,
      tIns: 22860,
    });
  }
}

function renderTable(list, slide, theme, m) {
  var rows = slide.rows || [];
  var cols = 0;
  for (var i = 0; i < rows.length; i++) {
    var row = isArray(rows[i]) ? rows[i] : [rows[i]];
    if (row.length > cols) {
      cols = row.length;
    }
  }
  if (cols === 0) {
    throw new Error("bad argument: renderPptx: table rows required");
  }
  if (rows.length > 12) {
    throw new Error("bad argument: renderPptx: too many items");
  }
  var gap = 18288;
  var colW = Math.floor((m.width - (cols - 1) * gap) / cols);
  var rowH = Math.floor((m.height - (rows.length - 1) * gap) / rows.length);
  for (var r = 0; r < rows.length; r++) {
    var rowData = isArray(rows[r]) ? rows[r] : [rows[r]];
    for (var c = 0; c < cols; c++) {
      var header = r === 0;
      var fill = header ? theme.accent : r % 2 === 0 ? theme.bg : theme.surface;
      var color = header ? theme.onAccent : theme.text;
      var x = m.left + c * (colW + gap);
      var y = m.contentTop + r * (rowH + gap);
      var cell = rowData[c] != null ? String(rowData[c]) : "";
      addText(list, x, y, colW, rowH, cell, {
        preset: "rect",
        fill: fill,
        sz: cols > 5 ? 1200 : 1400,
        bold: header,
        color: color,
        align: "ctr",
        anchor: "ctr",
        lIns: 45720,
        rIns: 45720,
      });
    }
  }
}

function renderImageLayout(list, slide, theme, m, media) {
  var side = slide.side === "left" ? "left" : "right";
  var gap = 182880;
  var picW = Math.floor(m.width * 0.52);
  var textW = m.width - picW - gap;
  var picX = side === "left" ? m.left : m.left + textW + gap;
  var textX = side === "left" ? m.left + picW + gap : m.left;
  var relId = media.add(slide.image);
  list.push({
    kind: "pic",
    x: picX,
    y: m.contentTop,
    cx: picW,
    cy: m.height,
    relId: relId,
    preset: "roundRect",
  });
  var ps = [];
  if (slide.text) {
    ps.push({ text: String(slide.text), sz: 1800, bold: false, color: theme.text, align: "l" });
  }
  var items = asList(slide.items);
  var bullets = listParagraphs(items, theme.text, 1800);
  for (var i = 0; i < bullets.length; i++) {
    ps.push(bullets[i]);
  }
  if (ps.length) {
    addText(list, textX, m.contentTop, textW, m.height, ps, { anchor: "ctr", lIns: 45720 });
  }
}

function renderUserShapes(list, slide, theme, m) {
  var shapes = slide.shapes || [];
  for (var i = 0; i < shapes.length; i++) {
    var sh = shapes[i] || {};
    var preset = sh.preset || "roundRect";
    var fillKey = sh.fill || "surface";
    var box = gridBox(m, sh.col, sh.row, sh.colSpan, sh.rowSpan);
    var text = sh.text != null ? String(sh.text) : "";
    if (text) {
      addText(list, box.x, box.y, box.cx, box.cy, text, {
        preset: preset,
        fill: fillHex(theme, fillKey),
        sz: 1800,
        bold: true,
        color: textOn(theme, fillKey),
        align: "ctr",
        anchor: "ctr",
      });
    } else {
      addShape(list, box.x, box.y, box.cx, box.cy, preset, fillHex(theme, fillKey), null);
    }
  }
}

function renderSlideShapes(slide, theme, bg, ink, index, total, media) {
  var list = [];
  addChrome(list, slide, theme, bg);
  var titled = !!(slide.title && String(slide.title).length);
  var contentLayout = slide.layout !== "title" && slide.layout !== "section";
  var m = metricsFor(contentLayout && titled);
  if (contentLayout && titled) {
    addTitleBlock(list, slide, theme, ink, m);
  }
  if (slide.layout === "title") {
    renderTitle(list, slide, ink);
  } else if (slide.layout === "section") {
    renderSection(list, slide, theme, ink);
  } else if (slide.layout === "bullets") {
    renderBullets(list, slide, theme, m);
  } else if (slide.layout === "twoColumn") {
    renderTwoColumn(list, slide, theme, m);
  } else if (slide.layout === "stat") {
    renderStat(list, slide, theme, m);
  } else if (slide.layout === "steps") {
    renderSteps(list, slide, theme, m);
  } else if (slide.layout === "callout") {
    renderCallout(list, slide, theme, m);
  } else if (slide.layout === "cards") {
    renderCards(list, slide, theme, m);
  } else if (slide.layout === "table") {
    renderTable(list, slide, theme, m);
  } else if (slide.layout === "image") {
    renderImageLayout(list, slide, theme, m, media);
  } else if (slide.layout === "shapes") {
    renderUserShapes(list, slide, theme, m);
  }
  if (contentLayout) {
    addFooter(list, index, total, m, ink);
  }
  return list;
}

function geomXml(preset) {
  if (preset === "roundRect") {
    return '<a:prstGeom prst="roundRect"><a:avLst><a:gd name="adj" fmla="val 8000"/></a:avLst></a:prstGeom>';
  }
  return '<a:prstGeom prst="' + preset + '"><a:avLst/></a:prstGeom>';
}

function fillXml(fill, alpha) {
  if (!fill) {
    return "<a:noFill/>";
  }
  var alphaXml = alpha ? '<a:alpha val="' + Math.round(alpha) + '"/>' : "";
  return '<a:solidFill><a:srgbClr val="' + fill + '">' + alphaXml + "</a:srgbClr></a:solidFill>";
}

function paragraphsXml(paragraphs) {
  if (!paragraphs || !paragraphs.length) {
    return '<a:p><a:endParaRPr lang="zh-CN" sz="1400"/></a:p>';
  }
  var xml = "";
  for (var i = 0; i < paragraphs.length; i++) {
    var p = paragraphs[i];
    var lines = String(p.text == null ? "" : p.text).split("\n");
    for (var j = 0; j < lines.length; j++) {
      xml +=
        "<a:p><a:pPr algn=\"" +
        (p.align || "l") +
        "\"/><a:r><a:rPr lang=\"zh-CN\" sz=\"" +
        (p.sz || 1800) +
        "\" b=\"" +
        (p.bold ? "1" : "0") +
        "\"><a:solidFill><a:srgbClr val=\"" +
        (p.color || "1A1A1A") +
        '"/></a:solidFill><a:latin typeface="Calibri"/><a:ea typeface="Microsoft YaHei"/><a:cs typeface="Calibri"/></a:rPr><a:t>' +
        xmlEscape(lines[j]) +
        "</a:t></a:r></a:p>";
    }
  }
  return xml;
}

function shapeToXml(shape, id) {
  var x = Math.round(shape.x);
  var y = Math.round(shape.y);
  var cx = Math.round(shape.cx);
  var cy = Math.round(shape.cy);
  if (shape.kind === "pic") {
    return (
      "<p:pic><p:nvPicPr><p:cNvPr id=\"" +
      id +
      '" name="Picture ' +
      id +
      '"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="' +
      shape.relId +
      '"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="' +
      x +
      '" y="' +
      y +
      '"/><a:ext cx="' +
      cx +
      '" cy="' +
      cy +
      '"/></a:xfrm>' +
      geomXml(shape.preset || "roundRect") +
      "<a:ln><a:noFill/></a:ln></p:spPr></p:pic>"
    );
  }
  var tx = "";
  if (shape.paragraphs) {
    tx =
      '<p:txBody><a:bodyPr wrap="square" lIns="' +
      (shape.lIns != null ? shape.lIns : 91440) +
      '" tIns="' +
      (shape.tIns != null ? shape.tIns : 45720) +
      '" rIns="' +
      (shape.rIns != null ? shape.rIns : 91440) +
      '" bIns="' +
      (shape.bIns != null ? shape.bIns : 45720) +
      '" anchor="' +
      (shape.anchor || "ctr") +
      '"/><a:lstStyle/>' +
      paragraphsXml(shape.paragraphs) +
      "</p:txBody>";
  }
  var spPr =
    "<p:cNvSpPr" + (shape.paragraphs ? ' txBox="1"' : "") + "/>";
  return (
    "<p:sp><p:nvSpPr><p:cNvPr id=\"" +
    id +
    '" name="Shape ' +
    id +
    '"/>' +
    spPr +
    "<p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"" +
    x +
    '" y="' +
    y +
    '"/><a:ext cx="' +
    cx +
    '" cy="' +
    cy +
    '"/></a:xfrm>' +
    geomXml(shape.preset || "rect") +
    fillXml(shape.fill, shape.alpha) +
    "<a:ln><a:noFill/></a:ln></p:spPr>" +
    tx +
    "</p:sp>"
  );
}

function backgroundXml(bg, theme, relId) {
  var inner = "";
  if (bg.kind === "image" && relId) {
    inner = '<a:blipFill><a:blip r:embed="' + relId + '"/><a:stretch><a:fillRect/></a:stretch></a:blipFill>';
  } else if (bg.kind === "accent") {
    inner = fillXml(theme.accent, null);
  } else if (bg.kind === "dark") {
    inner =
      '<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:srgbClr val="1B2430"/></a:gs><a:gs pos="100000"><a:srgbClr val="243044"/></a:gs></a:gsLst><a:lin ang="2700000" scaled="0"/></a:gradFill>';
  } else if (bg.kind === "light") {
    inner = fillXml("F7F5F2", null);
  } else if (bg.kind === "color") {
    inner = fillXml(theme[bg.colorKey], null);
  } else if (theme.gradient) {
    inner =
      '<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:srgbClr val="' +
      theme.bg +
      '"/></a:gs><a:gs pos="100000"><a:srgbClr val="' +
      theme.bg2 +
      '"/></a:gs></a:gsLst><a:lin ang="2700000" scaled="0"/></a:gradFill>';
  } else {
    inner = fillXml(theme.bg, null);
  }
  return "<p:bg><p:bgPr>" + inner + "<a:effectLst/></p:bgPr></p:bg>";
}

function slideXml(shapes, bgXml) {
  var body =
    '<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>';
  var id = 2;
  for (var i = 0; i < shapes.length; i++) {
    body += shapeToXml(shapes[i], id);
    id++;
  }
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<p:sld xmlns:a="' +
    A_NS +
    '" xmlns:r="' +
    R_NS +
    '" xmlns:p="' +
    P_NS +
    '"><p:cSld>' +
    bgXml +
    "<p:spTree>" +
    body +
    "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
  );
}

function relsXml(rels) {
  var xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="' + REL_NS + '">';
  for (var i = 0; i < rels.length; i++) {
    xml +=
      '<Relationship Id="' +
      rels[i].id +
      '" Type="' +
      rels[i].type +
      '" Target="' +
      xmlEscape(rels[i].target) +
      '"/>';
  }
  xml += "</Relationships>";
  return xml;
}

function themeXml(theme) {
  function clr(tag, val) {
    return "<a:" + tag + '><a:srgbClr val="' + val + '"/></a:' + tag + ">";
  }
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<a:theme xmlns:a="' +
    A_NS +
    '" name="Weizhi"><a:themeElements><a:clrScheme name="Weizhi">' +
    clr("dk1", theme.text) +
    clr("lt1", "FFFFFF") +
    clr("dk2", theme.accent) +
    clr("lt2", theme.bg) +
    clr("accent1", theme.accent) +
    clr("accent2", theme.accent2) +
    clr("accent3", theme.muted) +
    clr("accent4", theme.surface) +
    clr("accent5", theme.bg2) +
    clr("accent6", theme.onAccent) +
    clr("hlink", theme.accent) +
    clr("folHlink", theme.accent2) +
    "</a:clrScheme><a:fontScheme name=\"Weizhi\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"Microsoft YaHei\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"Microsoft YaHei\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme><a:fmtScheme name=\"Weizhi\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
  );
}

function masterXml() {
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<p:sldMaster xmlns:a="' +
    A_NS +
    '" xmlns:r="' +
    R_NS +
    '" xmlns:p="' +
    P_NS +
    '"><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr algn="l"><a:defRPr sz="4000"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr marL="0" indent="0"><a:defRPr sz="1800"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz="1400"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>'
  );
}

function layoutXml() {
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<p:sldLayout xmlns:a="' +
    A_NS +
    '" xmlns:r="' +
    R_NS +
    '" xmlns:p="' +
    P_NS +
    '" type="blank" preserve="1"><p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>'
  );
}

function presentationXml(slideCount) {
  var ids = "";
  for (var i = 0; i < slideCount; i++) {
    ids += '<p:sldId id="' + (256 + i) + '" r:id="rId' + (5 + i) + '"/>';
  }
  return (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<p:presentation xmlns:a="' +
    A_NS +
    '" xmlns:r="' +
    R_NS +
    '" xmlns:p="' +
    P_NS +
    '" saveSubsetFonts="1"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>' +
    ids +
    '</p:sldIdLst><p:sldSz cx="' +
    SLIDE_CX +
    '" cy="' +
    SLIDE_CY +
    '" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>'
  );
}

function contentTypesXml(slideCount) {
  var xml =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="' +
    CT_NS +
    '"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
    '<Default Extension="xml" ContentType="application/xml"/>' +
    '<Default Extension="png" ContentType="image/png"/>' +
    '<Default Extension="jpeg" ContentType="image/jpeg"/>' +
    '<Default Extension="jpg" ContentType="image/jpeg"/>' +
    '<Default Extension="gif" ContentType="image/gif"/>' +
    '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>' +
    '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>' +
    '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>' +
    '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>' +
    '<Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>' +
    '<Override PartName="/ppt/viewProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml"/>' +
    '<Override PartName="/ppt/tableStyles.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml"/>' +
    '<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>' +
    '<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>';
  for (var i = 1; i <= slideCount; i++) {
    xml +=
      '<Override PartName="/ppt/slides/slide' +
      i +
      '.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>';
  }
  xml += "</Types>";
  return xml;
}

var IMAGE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image";

function attachImage(bucket, path, seqBox) {
  var ext = imageExt(path);
  var name = "image" + seqBox.n + "." + ext;
  seqBox.n++;
  var relId = "rId" + bucket.nextRel;
  bucket.nextRel++;
  bucket.images.push({ name: name, bytes: fs.readFileSync(path) });
  bucket.rels.push({ id: relId, type: IMAGE_REL, target: "../media/" + name });
  return relId;
}

function writePptxTree(buildDir, deck) {
  var theme = themeByName(deck.theme);
  var slides = deck.slides;
  var seqBox = { n: 1 };
  var slideParts = [];

  for (var i = 0; i < slides.length; i++) {
    var slide = slides[i];
    var bg = resolveBackground(slide, theme);
    var bucket = {
      nextRel: 2,
      images: [],
      rels: [
        {
          id: "rId1",
          type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout",
          target: "../slideLayouts/slideLayout1.xml",
        },
      ],
    };
    var bgRel = null;
    if (bg.kind === "image") {
      bgRel = attachImage(bucket, bg.image, seqBox);
    }
    var media = {
      add: function (path) {
        return attachImage(bucket, path, seqBox);
      },
    };
    var shapes = renderSlideShapes(slide, theme, bg, slideInk(theme, bg), i, slides.length, media);
    slideParts.push({
      xml: slideXml(shapes, backgroundXml(bg, theme, bgRel)),
      rels: relsXml(bucket.rels),
      images: bucket.images,
    });
  }

  writeUtf8(buildDir + "/[Content_Types].xml", contentTypesXml(slides.length));
  writeUtf8(
    buildDir + "/_rels/.rels",
    relsXml([
      {
        id: "rId1",
        type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
        target: "ppt/presentation.xml",
      },
      {
        id: "rId2",
        type: "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties",
        target: "docProps/core.xml",
      },
      {
        id: "rId3",
        type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties",
        target: "docProps/app.xml",
      },
    ])
  );
  var presRels = [
    {
      id: "rId1",
      type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster",
      target: "slideMasters/slideMaster1.xml",
    },
    {
      id: "rId2",
      type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps",
      target: "presProps.xml",
    },
    {
      id: "rId3",
      type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/viewProps",
      target: "viewProps.xml",
    },
    {
      id: "rId4",
      type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/tableStyles",
      target: "tableStyles.xml",
    },
  ];
  for (var s = 0; s < slideParts.length; s++) {
    presRels.push({
      id: "rId" + (5 + s),
      type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide",
      target: "slides/slide" + (s + 1) + ".xml",
    });
    writeUtf8(buildDir + "/ppt/slides/slide" + (s + 1) + ".xml", slideParts[s].xml);
    writeUtf8(buildDir + "/ppt/slides/_rels/slide" + (s + 1) + ".xml.rels", slideParts[s].rels);
    for (var k = 0; k < slideParts[s].images.length; k++) {
      var im = slideParts[s].images[k];
      writeBytes(buildDir + "/ppt/media/" + im.name, im.bytes);
    }
  }
  writeUtf8(buildDir + "/ppt/presentation.xml", presentationXml(slideParts.length));
  writeUtf8(buildDir + "/ppt/_rels/presentation.xml.rels", relsXml(presRels));
  writeUtf8(buildDir + "/ppt/slideMasters/slideMaster1.xml", masterXml());
  writeUtf8(
    buildDir + "/ppt/slideMasters/_rels/slideMaster1.xml.rels",
    relsXml([
      {
        id: "rId1",
        type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout",
        target: "../slideLayouts/slideLayout1.xml",
      },
      {
        id: "rId2",
        type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme",
        target: "../theme/theme1.xml",
      },
    ])
  );
  writeUtf8(buildDir + "/ppt/slideLayouts/slideLayout1.xml", layoutXml());
  writeUtf8(
    buildDir + "/ppt/slideLayouts/_rels/slideLayout1.xml.rels",
    relsXml([
      {
        id: "rId1",
        type: "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster",
        target: "../slideMasters/slideMaster1.xml",
      },
    ])
  );
  writeUtf8(buildDir + "/ppt/theme/theme1.xml", themeXml(theme));
  writeUtf8(
    buildDir + "/ppt/presProps.xml",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentationPr xmlns:a="' +
      A_NS +
      '" xmlns:r="' +
      R_NS +
      '" xmlns:p="' +
      P_NS +
      '"><p:showPr/></p:presentationPr>'
  );
  writeUtf8(
    buildDir + "/ppt/viewProps.xml",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:viewPr xmlns:a="' +
      A_NS +
      '" xmlns:r="' +
      R_NS +
      '" xmlns:p="' +
      P_NS +
      '"><p:normalViewPr><p:restoredLeft sz="15620"/><p:restoredTop sz="94660"/></p:normalViewPr><p:slideViewPr><p:cSldViewPr><p:cViewPr varScale="1"><p:scale><a:sx n="100" d="100"/><a:sy n="100" d="100"/></p:scale><p:origin x="0" y="0"/></p:cViewPr><p:guideLst/></p:cSldViewPr></p:slideViewPr></p:viewPr>'
  );
  writeUtf8(
    buildDir + "/ppt/tableStyles.xml",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><a:tblStyleLst xmlns:a="' +
      A_NS +
      '" def="{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"/>'
  );
  writeUtf8(
    buildDir + "/docProps/core.xml",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
      '<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/">' +
      "<dc:title>" +
      xmlEscape(deck.title || "Presentation") +
      "</dc:title><dc:creator>Weizhi</dc:creator></cp:coreProperties>"
  );
  writeUtf8(
    buildDir + "/docProps/app.xml",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
      '<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Weizhi</Application><Slides>' +
      slides.length +
      "</Slides></Properties>"
  );
}

export function createDeck(options) {
  options = options || {};
  var theme = options.theme || "briefing";
  themeByName(theme);
  var slides = [];
  var src = options.slides || [];
  for (var i = 0; i < src.length; i++) {
    slides.push(src[i]);
  }
  return {
    title: options.title || "",
    theme: theme,
    slides: slides,
    addSlide: function (slide) {
      this.slides.push(slide || {});
      return this;
    },
  };
}

export function renderPptx(deckOrSpec, outputPath) {
  if (!outputPath) {
    throw new Error("bad argument: renderPptx: outputPath required");
  }
  var deck = deckOrSpec && deckOrSpec.slides && deckOrSpec.theme ? deckOrSpec : createDeck(deckOrSpec || {});
  if (!deck.slides || deck.slides.length === 0) {
    throw new Error("bad argument: renderPptx: slides required");
  }
  if (deck.slides.length > MAX_SLIDES) {
    throw new Error("bad argument: renderPptx: too many slides");
  }
  themeByName(deck.theme);
  for (var i = 0; i < deck.slides.length; i++) {
    if (!deck.slides[i] || typeof deck.slides[i] !== "object") {
      throw new Error("bad argument: renderPptx: unknown layout");
    }
    validateSlide(deck.slides[i]);
  }
  var buildDir = "tmp/pptx-render-" + Date.now();
  writePptxTree(buildDir, deck);
  packDir(buildDir, outputPath);
  var bytes = fs.readFileSync(outputPath).length;
  return { ok: true, path: outputPath, bytes: bytes, slides: deck.slides.length };
}

export default {
  createDeck: createDeck,
  renderPptx: renderPptx,
};
