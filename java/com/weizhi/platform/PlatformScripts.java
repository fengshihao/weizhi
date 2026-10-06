package com.weizhi.platform;

/**
 * JS prelude that installs exactly one live platform object and stubs the others.
 * Shared shape: {@code ui.confirm}, {@code files.*} (incl. {@code zipExtract}/{@code zipCreate}),
 * {@code media.resize}, {@code share.send}, {@code intent.start}, {@code reminders.*}, {@code audit.recent}.
 * Hosts that lack a capability return {@code unsupported}.
 */
public final class PlatformScripts {
    private PlatformScripts() {
    }

    public static String install(String platform) {
        if (!"android".equals(platform) && !"mac".equals(platform) && !"linux".equals(platform)) {
            throw new IllegalArgumentException("platform");
        }
        String otherA = "android".equals(platform) ? "mac" : "android";
        String otherB = "linux".equals(platform) ? "mac" : "linux";
        if ("mac".equals(platform)) {
            otherB = "linux";
        }
        return "(function(){\n"
                + "function call(op, extra){\n"
                + "  var req = {op: op};\n"
                + "  if (extra) { for (var k in extra) { if (Object.prototype.hasOwnProperty.call(extra,k)) req[k]=extra[k]; } }\n"
                + "  var r = __caps(req);\n"
                + "  if (r && r.error) throw new Error(r.error);\n"
                + "  return r;\n"
                + "}\n"
                + "function stub(name){\n"
                + "  function fail(){ throw new Error('unsupported: ' + name + '.* on this host (platform is " + platform + ")'); }\n"
                + "  fail.ui = { confirm: fail };\n"
                + "  fail.files = { list: fail, read: fail, write: fail, mkdir: fail, rename: fail, move: fail, undo: fail, pickDirectory: fail, zipExtract: fail, zipCreate: fail };\n"
                + "  fail.media = { resize: fail };\n"
                + "  fail.share = { send: fail };\n"
                + "  fail.intent = { start: fail };\n"
                + "  fail.reminders = { schedule: fail, cancel: fail, fire: fail };\n"
                + "  fail.audit = { recent: fail };\n"
                + "  return fail;\n"
                + "}\n"
                + "var api = {\n"
                + "  platform: '" + platform + "',\n"
                + "  ui: { confirm: function(message){ return !!call('ui.confirm', {message: String(message)}).ok; } },\n"
                + "  files: {\n"
                + "    list: function(dir){ return call('files.list', {dir: dir || '.'}).items; },\n"
                + "    read: function(path){ return call('files.read', {path: path}).text; },\n"
                + "    write: function(path, text){ call('files.write', {path: path, text: String(text)}); return true; },\n"
                + "    mkdir: function(dir){ return call('files.mkdir', {dir: dir}); },\n"
                + "    rename: function(path, name){ return call('files.rename', {path: path, name: name}); },\n"
                + "    move: function(path, toDir){ return call('files.move', {path: path, toDir: toDir}); },\n"
                + "    undo: function(){ return call('files.undo', {}); },\n"
                + "    pickDirectory: function(){ return call('files.pickDirectory', {}).uri; },\n"
                + "    zipExtract: function(file, dest){ return call('files.zipExtract', {file: file, dest: dest == null ? '' : dest}).message; },\n"
                + "    zipCreate: function(sourceDir, file){ return call('files.zipCreate', {sourceDir: sourceDir, file: file}).message; }\n"
                + "  },\n"
                + "  media: { resize: function(path, maxEdge){ return call('media.resize', {path: path, maxEdge: maxEdge}); } },\n"
                + "  share: { send: function(spec){ spec = spec || {}; return call('share.send', {text: String(spec.text||''), title: String(spec.title||'')}); } },\n"
                + "  intent: { start: function(spec){\n"
                + "    spec = spec || {};\n"
                + "    var extra = { action: String(spec.action||'') };\n"
                + "    if (spec.path != null) extra.path = String(spec.path);\n"
                + "    if (spec.type != null) extra.type = String(spec.type);\n"
                + "    if (spec.text != null) extra.text = String(spec.text);\n"
                + "    if (spec.title != null) extra.title = String(spec.title);\n"
                + "    if (spec.panel != null) extra.panel = String(spec.panel);\n"
                + "    if (spec.data != null) extra.data = String(spec.data);\n"
                + "    if (spec.screen != null) extra.screen = String(spec.screen);\n"
                + "    if (spec.paths != null) extra.paths = spec.paths;\n"
                + "    if (spec.chooser != null) extra.chooser = !!spec.chooser;\n"
                + "    return call('intent.start', extra);\n"
                + "  } },\n"
                + "  reminders: {\n"
                + "    schedule: function(spec){ spec = spec || {}; return call('reminders.schedule', {title: String(spec.title||''), body: String(spec.body||''), atMs: spec.atMs}); },\n"
                + "    cancel: function(id){ return call('reminders.cancel', {id: String(id)}); },\n"
                + "    fire: function(id){ return call('reminders.fire', {id: String(id)}); }\n"
                + "  },\n"
                + "  audit: { recent: function(){ return call('audit.recent', {}).items; } }\n"
                + "};\n"
                + "globalThis." + platform + " = api;\n"
                + "globalThis." + otherA + " = stub('" + otherA + "');\n"
                + "globalThis." + otherB + " = stub('" + otherB + "');\n"
                + "api.platform;\n"
                + "})();\n";
    }
}
