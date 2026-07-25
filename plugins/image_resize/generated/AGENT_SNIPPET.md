# Agent API: `image_resize`

```js
const p = await host.ensureNative("image_resize");
```

- `p.resize_rgba(rgba: bytes, width: i32, height: i32, max_edge: i32)` → `bytes`
  - `bytes` 在 JS 里是 `Buffer`
