package com.weizhi.platform;

/**
 * One-shot document sort used by the demo and tests.
 * Groups loose files in the current directory by extension, asks for confirmation,
 * then moves them. A failed move undoes the moves already made in this run.
 */
public final class OrganizeFiles {
    private OrganizeFiles() {
    }

    public static String run(String platform) {
        return "(function(){\n"
                + "var files = " + platform + ".files;\n"
                + "var ui = " + platform + ".ui;\n"
                + "function folderFor(name){\n"
                + "  var dot = name.lastIndexOf('.');\n"
                + "  if (dot < 0) return '';\n"
                + "  var ext = name.substring(dot + 1).toLowerCase();\n"
                + "  if (ext === 'pdf' || ext === 'doc' || ext === 'docx' || ext === 'txt' || ext === 'md' || ext === 'csv') return '文档';\n"
                + "  if (ext === 'jpg' || ext === 'jpeg' || ext === 'png' || ext === 'gif' || ext === 'webp') return '图片';\n"
                + "  if (ext === 'mp4' || ext === 'mov' || ext === 'mkv') return '视频';\n"
                + "  return '';\n"
                + "}\n"
                + "var items = files.list('.');\n"
                + "var plan = [];\n"
                + "for (var i = 0; i < items.length; i++) {\n"
                + "  var it = items[i];\n"
                + "  if (!it || it.dir) continue;\n"
                + "  var folder = folderFor(String(it.name));\n"
                + "  if (!folder) continue;\n"
                + "  plan.push({name: String(it.name), folder: folder});\n"
                + "}\n"
                + "plan.sort(function(a, b){ return a.name < b.name ? -1 : (a.name > b.name ? 1 : 0); });\n"
                + "if (plan.length === 0) return {moved: 0, cancelled: false, empty: true};\n"
                + "if (!ui.confirm('将整理 ' + plan.length + ' 个文件，是否继续？')) return {moved: 0, cancelled: true};\n"
                + "var moved = 0;\n"
                + "try {\n"
                + "  for (var k = 0; k < plan.length; k++) {\n"
                + "    files.mkdir(plan[k].folder);\n"
                + "    files.move(plan[k].name, plan[k].folder);\n"
                + "    moved++;\n"
                + "  }\n"
                + "} catch (e) {\n"
                + "  for (var u = 0; u < moved; u++) files.undo();\n"
                + "  throw e;\n"
                + "}\n"
                + "return {moved: moved, cancelled: false};\n"
                + "})()\n";
    }
}
