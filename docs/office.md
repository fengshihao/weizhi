# host.office — QuickJS 文档产出 API

日期：2026-09-28。对应 GitHub issue #6（Agent1 文档 / 表格 / 幻灯片产出）。

## 选型（Weizhi 侧）

| 方案 | 结论 |
|------|------|
| 纯 qjs_module（JS + `zip` / XML） | 可行，但 OOXML 模板体积大、在 QuickJS 里维护成本高 |
| Native SO（C++ 库） | 体积与 NDK 维护成本高 |
| **Java `OfficeService` + `__caps`** | **采用**：与现有 caps / 沙箱一致，AAR 无新增 Maven 依赖，桌面 JNI 与 Android 共用源码 |

生成方式：在工作区内写入最小 OOXML 目录树，再用已有 `ZipTools` 打成 `.docx` / `.xlsx` / `.pptx`（OOXML = ZIP + XML）。

## 前置条件

- 已安装 caps：`AndroidCaps.install` 或 `DesktopCaps.install`（提供 `__caps` 与沙箱路径校验）。
- 脚本内使用 **`host.office.*`**（由 caps install 时注入；未 install 时无 `host.office`）。

## API（MVP）

返回值均为 JSON 友好对象：

- 成功：`{ ok: true, path: "<workspace 相对路径>", bytes: <number> }`
- 失败：`{ ok: false, errorType: "office", message: "<英文或 path escape 等>" }`

### docx

```javascript
host.office.docx.fromMarkdown({
  inputPath: "notes/report.md",   // workspace 相对；与 markdown 二选一
  outputPath: "out/report.docx",
  title: "可选封面标题",
});
```

Markdown 子集：`# 标题`、`-` / `*` 列表、普通段落。

### xlsx

```javascript
host.office.xlsx.fromRows({
  outputPath: "out/data.xlsx",
  sheetName: "Sheet1",
  rows: [["A", "B"], [1, 2]],
  // 或 inputPath: "data.csv"  （简单逗号分隔，无引号转义）
});
```

### pptx

```javascript
host.office.pptx.fromMarkdown({
  inputPath: "slides/deck.md",
  outputPath: "out/deck.pptx",
});
```

幻灯片约定：用单独一行的 `---` 分页；每页首个 `# 标题` 为标题，`-` 为要点。

## 沙箱

`outputPath` / `inputPath` 均经 `LocalWorkspace.resolve`：**`../outside.docx` 等越界路径会失败**（`path escape`），并有集成测试覆盖。

## 测试（本仓库）

```bash
./scripts/test-jni.sh          # 含 tests.java.OfficeTest（PK 魔数 + zip 内 XML 断言）
./scripts/test.sh              # 默认也会跑 JNI smoke → OfficeTest
./scripts/test.sh android      # instrumented：host.office.docx.fromMarkdown
```

### 更严格的格式校验（可选，CI / 本机）

MVP 测试只验证 **ZIP 结构 + 关键 part 路径 + 文本是否写入 XML**。若要在 PC 上做更强校验，推荐：

| 工具 | 用途 |
|------|------|
| `unzip -l file.docx` | 快速列出 OOXML part（CI 已用 Java `ZipInputStream` 等价实现） |
| `xmllint --noout word/document.xml` | XML well-formed（需先 unzip） |
| [python-ooxml](https://github.com/python-openxml/python-docx) / **python-docx** | 程序化打开 docx 断言段落（适合 Agent1 `weizhi-bridge` 薄集成测） |
| **LibreOffice** `soffice --headless --convert-to pdf` | 冒烟「能否被 Office 族打开」（慢，适合 nightly） |
| **ooxml-validator**（Microsoft 开源） | 严格 Schema 校验（适合发布前 gate） |

不建议把 LibreOffice 或 python-docx 绑进 Weizhi 引擎 AAR；**严格校验放在 Agent1 bridge 或 CI 可选 job** 即可。

## Agent1 集成（引擎完成后）

- `execute_script` 内直接调 `host.office.*`，或 catalog 薄脚本封装。
- 生成 docx 后由 Agent1 UI `FileProvider` + `ACTION_VIEW` 打开（不在 Weizhi 实现）。

## 后续（非 MVP）

- `docx.open` / `insertParagraph` / `insertImage` / `save`
- `xlsx.open` / `appendSheet` / `writeCell`
- 复杂 ppt 版式、主题与图片嵌入
