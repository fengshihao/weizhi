# PR  playbook

## 标题

- 祈使句：`Add snapshot checkpoint API to caps` / `修复 runJs 并发 busy 误报`
- 或前缀：`fix:` / `docs:` / `android:` / `engine:`

## 正文模板

```markdown
## Summary
（1–3 句：改了什么、为什么）

## User-visible / integrator-visible changes
- …

## Test plan
- [ ] WEIZHI_SKIP_ASAN=1 ./scripts/test.sh
- [ ] （如适用）./scripts/test.sh android
- [ ] （如适用）./scripts/test-office-strict.sh

（粘贴关键命令输出或 CI 链接）
```

## 审查关注点

- 分层是否正确（引擎 vs caps vs agent-tools）
- 错误信息是否含约定英文关键词
- 是否有对应自动化测试
- 文档是否需同步（API / 集成指南）
