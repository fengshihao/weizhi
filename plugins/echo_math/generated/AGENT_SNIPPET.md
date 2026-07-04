# Agent API: `echo_math`

```js
const p = await host.ensureNative("echo_math");
```

- `p.add(a: i32, b: i32)` → `i32`
- `p.echo_bytes(data: bytes)` → `bytes`
  - `bytes` 在 JS 里是 `Buffer`
- `p.count_with_cb(n: i32, on_i: cb(i: i32))` → `i32`
  - 回调参数传 JS `function`，在 C 侧触发时于 JS 线程执行
