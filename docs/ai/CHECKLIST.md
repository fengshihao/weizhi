# Weizhi 贡献检查清单（AI / 人类）

在 push 或开 PR 前逐项确认。

## 必读

- [ ] [`AGENTS.md`](../../AGENTS.md)
- [ ] 改动涉及集成行为时：[`docs/INTEGRATION_FOR_AI.md`](../INTEGRATION_FOR_AI.md)
- [ ] 改动涉及工具环时：[`docs/AGENT_TOOLS_INTEGRATION.md`](../AGENT_TOOLS_INTEGRATION.md)
- [ ] 分层与限额：[`docs/DECISIONS.md`](../DECISIONS.md)

## 范围

- [ ] 一个 PR 只做一件事
- [ ] 未修改 `third_party/quickjs`
- [ ] 未把 grep / 行号 edit 等 Agent 工具塞进 C 引擎

## 测试（强制）

- [ ] 已运行 `WEIZHI_SKIP_ASAN=1 ./scripts/test.sh` 且通过
- [ ] 本地有时间跑完整 `./scripts/test.sh`（含 ASan）优先跑全量
- [ ] 改了 `android/`、`jni/`、`java/`：已跑或说明为何无法跑 `./scripts/test.sh android`
- [ ] 未把 docx/pptx 脚本或调用卡加回本仓库（归 Agent1）
- [ ] **新功能或 bugfix** 已添加/更新 `tests/` 或 `androidTest/`

## 构建（按需）

- [ ] Android 产物：`./scripts/build-android.sh arm64-v8a` 后 `cd android && ./gradlew :weizhi:assembleRelease`
- [ ] Release AAR 使用 **stripped** `libweizhijni.so`（见 `scripts/build-android.sh`）

## PR

- [ ] 标题与正文符合 [`PR_PLAYBOOK.md`](./PR_PLAYBOOK.md)
- [ ] Test plan 列出**实际执行**的命令与结果
