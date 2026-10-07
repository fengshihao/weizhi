# 对 AI 说一句话就能开工

## 给人看的两步

1. **准备环境**：把下面「复制给 AI」发给 Cursor / Claude / 其他编码智能体（不必自己装 NDK）。
2. **再说意图 + 验收**：环境就绪后说明要改什么；确认 `./scripts/test.sh`（及需要的 android / office 测试）通过后再开 PR。

---

## 复制给 AI（准备环境）

```text
帮我准备开源项目微智 Weizhi（https://github.com/fengshihao/weizhi）的贡献环境：请你自己克隆仓库、读 AGENTS.md 和 docs/ai/START.md，缺依赖时运行 ./scripts/fetch-deps.sh，需要时用 WEIZHI_SKIP_ASAN=1 ./scripts/test.sh 验证。准备好后告诉我，我再说想贡献什么。
```

**English**

```text
Set up a contribution environment for Weizhi (https://github.com/fengshihao/weizhi): clone the repo, read AGENTS.md and docs/ai/START.md, run ./scripts/fetch-deps.sh if needed, and WEIZHI_SKIP_ASAN=1 ./scripts/test.sh when validating. Tell me when ready; I will describe the change next.
```

---

## 环境就绪后对 AI 说

```text
我要贡献：〈一件事〉。按 AGENTS.md 与 docs/ai/CHECKLIST.md 改；新行为必须有自动化测试；改完必须 WEIZHI_SKIP_ASAN=1 ./scripts/test.sh 通过（动 Android/JNI 再跑 ./scripts/test.sh android）；按 docs/ai/PR_PLAYBOOK.md 开 PR。不要改 third_party/quickjs。
```

**English**

```text
I want to contribute: 〈one scoped change〉. Follow AGENTS.md and docs/ai/CHECKLIST.md; add or update automated tests for new behavior; run WEIZHI_SKIP_ASAN=1 ./scripts/test.sh (plus ./scripts/test.sh android if JNI/Android changed); open a PR per docs/ai/PR_PLAYBOOK.md. Do not modify third_party/quickjs.
```

---

## 第三方 App：一句话接 Weizhi 引擎

给**集成方**的 AI（不是改本仓库）：

```text
在我的 Android Agent 里集成 Weizhi：读 https://github.com/fengshihao/weizhi/blob/master/docs/INTEGRATION_FOR_AI.md，依赖 :weizhi（+ 可选 :caps）。模型工具由宿主自己提供；Agent1 的 grep/bash 不在本仓库。workspace 与 setFsRoot 对齐，用 WeizhiEngine.runJs 跑脚本，系统提示粘贴 docs/AGENT_SANDBOX_PROMPT.md。
```

---

## AI 做完后人类怎么验

```bash
WEIZHI_SKIP_ASAN=1 ./scripts/test.sh
# 若改了 android/、jni/、java/：
./scripts/test.sh android
# Office 脚本不在本仓库。引擎冒烟含在 test.sh 的 CatalogSmokeTest。
```

细则：[CHECKLIST.md](./CHECKLIST.md) · [PR_PLAYBOOK.md](./PR_PLAYBOOK.md)
