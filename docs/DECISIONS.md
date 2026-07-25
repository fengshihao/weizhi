# Weizhi: implementation decisions

When people step away, work continues against the agreed design. Below are the specs already locked in code and verified by tests.

- Agent-facing sandbox contract: [AGENT_SANDBOX_PROMPT.md](AGENT_SANDBOX_PROMPT.md)
- Host / native plugin ABI: [HOST_ABI.md](HOST_ABI.md)
- Typed SO / IDL (近原生、Buffer、回调): [NATIVE_PLUGIN_IDL.md](NATIVE_PLUGIN_IDL.md) — 决策 D1–D8 已锁定；真机 android 测通过。

## Product boundaries

- Repo lives at `/Users/fengshihao/Work/weizhi`, alongside Agent1, not inside it.
- License is Apache-2.0. QuickJS stays upstream MIT and must not be altered.
- Do not depend on or fork all of quickjs-kt. Compile Bellard QuickJS only inside this engine.
- Agents have one entry point: run a JS snippet. Plain JS libraries load via `loadScript("file.js")` from a script folder. **Heavy / platform capability is host-signed native SO** (see HOST_ABI), not in-engine Wasm.
- **Wasm / WAMR / `loadPack` are archived** on branch `archive/wamr-packs`. Trunk does not link WAMR. Restore from that branch if needed later.
- Official site, full README, and the Molan-style portal are out of scope for this phase.
- Host binding is **Java + JNI**, no Kotlin. Async I/O uses an `ExecutorService` thread pool (default fixed size from `maxAsyncIo`).
- **Android delivery**: Gradle module `:weizhi` (`com.android.library`) packages `WeizhiEngine` + `libweizhijni.so` as an **AAR**. `:caps` packages the Android productivity surface (`android.ui` / `android.files` / `android.media` / `android.share` / `android.reminders`). Demo `:app` depends on both. Desktop hosts install `mac` (macOS) or `linux` (Ubuntu and other Linux) via `DesktopCaps` — same method shape, unavailable ops return `unsupported`. Exactly one platform object is live; the other names throw. Build native first (`./scripts/build-android.sh`), then `cd android && gradle :weizhi:assembleRelease :caps:assembleRelease`.
- **`fetch`**: C provides `globalThis.fetch` (Promise + Response-like `text`/`json`/`arrayBuffer`). Real HTTP is host-installed via `weizhi_set_http` / Java `enableFetch`. Without install, errors say how to enable it.
- **`host.ensureNative`**: async plugin load via Host ABI NATIVE. Host does catalog/download/verify/dlopen (Android mock: `enableNativeMock()`). See [HOST_ABI.md](HOST_ABI.md).
- Built-ins such as `Buffer` / `path` / `require`·`import` / Promise drain: **one C implementation** for PC and Android; platforms only swap host wiring.

## Memory

Third-party JS library fit (bundle, slim, or native SO): [QUICKJS_LIB_COMPAT.md](QUICKJS_LIB_COMPAT.md).

Default per-engine caps:

| Item | Default | Meaning |
|---|---|---|
| JS heap | 32 MB | Strings and objects in the script count here. Over limit stops with `memory` in the error |
| JS stack | 256 KB | Deep recursion stops with `stack` in the error |
| Registered host functions | 32 | Beyond this, `addFunction` fails |
| Single fs read/write payload | 32 MB | Cap on **one** `read`/`write` byte count, not total workspace size. Over limit fails with `too large` |
| In-flight async I/O workers | 16 | Default async VFS / Java pool concurrency. Excess **queues** (no error). Set via `max_async_io` / `maxAsyncIo`. |

Set at engine creation via `WeizhiLimits` (C: `weizhi_open`; Java: `new WeizhiEngine(limits)`). Field `0` means use the default. Cannot change after open.

Dev builds use the system allocator, not a pre-reserved pool. Closing the engine returns memory to the system (`WeizhiEngine.close` / `weizhi_close`). Prefer close after each agent task; do not keep an idle engine forever. Native plugin SO unload policy is in HOST_ABI (refcount + idle TTL); `libweizhijni.so` stays process-resident.

Script return values are always text (JSON). Image bytes go through host functions or `Buffer`; `runJs` does not emit a separate byte payload.

## Threads and async

- **JS runs on one thread only** (the thread that called `runJs`). Concurrent entry into the same engine’s QuickJS is forbidden.
- Sync host functions (default `addFunction`, `fs.*Sync`) still run on the JS thread to completion.
- **Async host** (`fs.promises`, `fetch`): run on a bounded worker pool; when done, enqueue completion and wake `runJs`; **never** call QuickJS directly from pool threads.
- Default in-flight async I/O concurrency is **16** (`WeizhiLimits.max_async_io` / Java `maxAsyncIo`; `0` = default). Extra jobs **queue and wait**; they do not fail the script. Hosts may set `1` (fully serial) or raise the limit. A custom `weizhi_set_vfs` async callback / custom Java `ExecutorService` is host-managed and bypasses this default pool.
- One engine runs one script at a time.
  - Same thread calls `runJs` again: fails with `again` in the error.
  - Another thread calls `runJs`: fails with `busy` in the error.
- `close` returns -1 while a script is running; the engine is not torn down and can run again after the script ends.

## Promise / async / await

- Syntax and `Promise` are available.
- If `runJs` gets a Promise, within the timeout it drains microtasks, due `setTimeout`s, and the completion queue until that Promise settles.
- fulfilled → JSON of the fulfilled value; rejected → failure.
- `setTimeout` / `clearTimeout` live only for the current `runJs`.
- No persistent event loop across multiple `runJs` calls.

## Time

- `runJs` timeout of 0 means the default 3000 ms.
- Negative means no limit.
- On timeout the error contains `timeout`; the engine can still run the next script.

## Node-style built-ins (phase 1)

- Prefer `import fs from "fs"`; `require("fs")` is compatible. Built-in names only, no npm.
- Built-ins: `fs` (including `fs.promises`), `path`, `buffer`, `process` (read-only subset), `console`, `zlib` (`require("zlib")`: `gzipSync` / `gunzipSync` / `deflateSync` / `inflateSync`).
- `Buffer` / `path` / module table / `fs` surface: C implementation.
- Filesystem: host sandbox root; relative paths; escape fails with `path` or `escape` in the error.
- Missing module / missing member: fails with `unsupported` and a clear name (see next section).

## Error messages for agents (precise external hints)

When `runJs` fails, `WeizhiResult.error` is for humans and for agent self-correction. Convention:

| Case | Keyword / shape that must appear in the error |
|---|---|
| Missing module | `unsupported: module "http" (available: buffer, fs, path, process, zlib)` |
| Missing API on a module | `unsupported: fs.watch` (via Proxy, avoid `undefined is not a function`) |
| Bad arg type/count | `bad argument: Buffer.from: only strings are supported` |
| Sandbox path issue | `path` or `escape` |
| Timeout / memory / stack | `timeout` / `memory` / `stack` |
| Fetch disabled | `unsupported: fetch (... enableFetch / weizhi_set_http ...)` |
| Fetch host blocked | `fetch blocked: host "..." is not allowlisted ...` |
| Fetch body/response too big | `too large: fetch ...` |

Hosts should pass the full `error` (and `error_location`) back to the orchestrating agent; do not swallow or rewrite into a vague “failed”.

## Script libraries

- Folder is set by `weizhi_set_script_folder` / Java `setScriptFolder`. JS uses leaf names only (`loadScript("util.js")`), not paths.
- Names allow only letters, digits, `.`, `_`, and `-`. Slash or `..` fails with `name` in the error.
- Script root and workspace (`fs`) root are separate.
- Files over 16 MB are rejected.

## Logging

One JSON object per line. Dev builds log script source, host-function args and returns. Field names are English: `run_id`, `seq`, `event`. Events include `run_js_start`, `host_call`, `load_script`, `console`. Human-readable failure reasons are English.

## Size

- Android measures Release + stripped `.so` / binary.
- Phase-1 engine growth vs pre-enhancement strip baseline: target ≤ 256KB; `Buffer` only utf8/base64/hex and similar subsets. Removing WAMR from trunk shrinks the JNI `.so` further; re-run `scripts/size-check.sh` after release builds.

## How dependencies are placed

`third_party/quickjs` is shallow-cloned by `scripts/fetch-deps.sh` and not committed. WAMR is not fetched on trunk; see `archive/wamr-packs` if restoring Wasm.
