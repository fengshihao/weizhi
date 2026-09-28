package com.weizhi.platform;

/**
 * Installs {@code host.office.*} on top of the guarded {@code host} object from the engine.
 * Requires {@code __caps} (via {@link PlatformHost} / caps install).
 */
public final class OfficeScripts {
    private OfficeScripts() {
    }

    public static String install() {
        return "(function(){\n"
                + "function mergeReq(op, opts){\n"
                + "  var req = {op: op};\n"
                + "  opts = opts || {};\n"
                + "  for (var k in opts) {\n"
                + "    if (Object.prototype.hasOwnProperty.call(opts, k)) req[k] = opts[k];\n"
                + "  }\n"
                + "  return req;\n"
                + "}\n"
                + "function call(op, opts){\n"
                + "  var r = __caps(mergeReq(op, opts));\n"
                + "  if (r && r.error) {\n"
                + "    return {ok: false, errorType: 'office', message: r.error};\n"
                + "  }\n"
                + "  return r;\n"
                + "}\n"
                + "host.office = {\n"
                + "  docx: {\n"
                + "    fromMarkdown: function(opts){ return call('office.docx.fromMarkdown', opts); }\n"
                + "  },\n"
                + "  xlsx: {\n"
                + "    fromRows: function(opts){ return call('office.xlsx.fromRows', opts); }\n"
                + "  },\n"
                + "  pptx: {\n"
                + "    fromMarkdown: function(opts){ return call('office.pptx.fromMarkdown', opts); }\n"
                + "  }\n"
                + "};\n"
                + "return host.office;\n"
                + "})();\n";
    }
}
