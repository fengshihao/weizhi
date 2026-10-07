/**
 * pptx-build.js — fluent builder / slide list on top of pptx.js.
 *
 * import { buildPptx } from "./pptx-build.js";
 */
import { createDeck, renderPptx } from "./pptx.js";

function DeckBuilder(deck) {
  this._deck = deck;
}

DeckBuilder.prototype._spec = function (layout, fields, extra) {
  var spec = { layout: layout };
  if (extra) {
    Object.assign(spec, extra);
  }
  Object.assign(spec, fields);
  this._deck.slides.push(spec);
  return this;
};

DeckBuilder.prototype.slide = function (spec) {
  this._deck.slides.push(spec || {});
  return this;
};

DeckBuilder.prototype.slides = function (list) {
  var items = list || [];
  for (var i = 0; i < items.length; i++) {
    this.slide(items[i]);
  }
  return this;
};

DeckBuilder.prototype.title = function (title, subtitle, extra) {
  return this._spec("title", { title: title, subtitle: subtitle }, extra);
};

DeckBuilder.prototype.section = function (title, subtitle, extra) {
  return this._spec("section", { title: title, subtitle: subtitle }, extra);
};

DeckBuilder.prototype.bullets = function (title, items, extra) {
  return this._spec("bullets", { title: title, items: items }, extra);
};

DeckBuilder.prototype.twoColumn = function (title, left, right, extra) {
  return this._spec("twoColumn", { title: title, left: left, right: right }, extra);
};

DeckBuilder.prototype.image = function (title, path, extra) {
  return this._spec("image", { title: title, image: path }, extra);
};

DeckBuilder.prototype.table = function (title, rows, extra) {
  return this._spec("table", { title: title, rows: rows }, extra);
};

DeckBuilder.prototype.stat = function (title, items, extra) {
  return this._spec("stat", { title: title, items: items }, extra);
};

DeckBuilder.prototype.steps = function (title, items, extra) {
  return this._spec("steps", { title: title, items: items }, extra);
};

DeckBuilder.prototype.callout = function (text, extra) {
  return this._spec("callout", { text: text }, extra);
};

DeckBuilder.prototype.cards = function (title, items, extra) {
  return this._spec("cards", { title: title, items: items }, extra);
};

DeckBuilder.prototype.shapes = function (title, shapes, extra) {
  return this._spec("shapes", { title: title, shapes: shapes }, extra);
};

DeckBuilder.prototype.deck = function () {
  return this._deck;
};

export function buildDeck(options, fn) {
  var deck = createDeck(options || {});
  var builder = new DeckBuilder(deck);
  if (typeof fn === "function") {
    fn(builder);
  }
  return deck;
}

export function buildPptx(options, fn, outputPath) {
  var deck = buildDeck(options, fn);
  if (!outputPath) {
    return deck;
  }
  return renderPptx(deck, outputPath);
}

export { DeckBuilder };

export default {
  buildDeck: buildDeck,
  buildPptx: buildPptx,
  DeckBuilder: DeckBuilder,
};
