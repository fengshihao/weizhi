# Agent API: `video`

```js
const p = await host.ensureNative("video");
```

- `p.probe(path: str)` → `str`
  - `str` 在 JS 里是 string；返回值是 JSON 字符串，需自行 `JSON.parse`
- `p.open_job(options_json: str)` → `i64`
  - `str` 在 JS 里是 string；返回值是 JSON 字符串，需自行 `JSON.parse`
- `p.run_job(job: i64, on_progress: cb(s: str))` → `str`
  - `str` 在 JS 里是 string；返回值是 JSON 字符串，需自行 `JSON.parse`
  - 回调参数传 JS `function`，在 C 侧触发时于 JS 线程执行
  - `cb(s: str)` 的回调收到解析后的 JSON 对象，且仅在该调用返回前有效
- `p.close_job(job: i64)` → `void`
