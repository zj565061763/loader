# Changelog

## 1.7.0

### ⚠️ Breaking Changes

- **移除 `LoadScope` 和 `onLoadFinish`**：`load` / `tryLoad` 的 block 签名由 `suspend LoadScope.() -> T` 改为 `suspend () -> T`。不再提供 `onLoadFinish` 回调，请在 block 内使用 `try/finally` 处理清理逻辑。
- **`cancel()` 重命名为 `cancelAndJoin()`**：语义更明确，取消当前加载并挂起等待其结束。
- **`tryLoad` 取消行为变更**：加载进行中时抛出新的 `FLoader.BusyCancellationException`（取代原先的裸 `CancellationException`）。它是 `CancellationException` 的子类，若不捕获会静默取消调用方协程。此外，若上一次任务正在取消中，`tryLoad` 也会判定为“忙”并抛出该异常（此前会等待旧任务清理完再放行）。

### ✨ Improvements

- **新增 `FLoader.BusyCancellationException`**：可明确区分“因加载繁忙而取消”的场景，便于调用方按需捕获处理。
- **内部重构**：将互斥、取消、嵌套检测逻辑从 `FLoader` 抽离为独立的 `internal` 组件（`FMutator`、`FMutex`），实现更清晰、更易维护。均为内部实现，不属于公开 API。

### 🐛 Bug Fixes

- **修复多 Loader 场景下嵌套检测失效**：此前嵌套调用检测在多个 loader 同时使用时会失效，现已修复（每个 `FMutex` 实例使用独立的上下文 key）。
- **加载中调用 `tryLoad` 返回明确结果**：不再静默无响应，而是抛出可识别的 `BusyCancellationException`。

### Migration

```kotlin
// 1.6.0
loader.load { /* LoadScope */ onLoadFinish { cleanup() } /* ... */ }
loader.cancel()

// 1.7.0
loader.load { try { /* ... */ } finally { cleanup() } }
loader.cancelAndJoin()
// tryLoad 忙时抛 FLoader.BusyCancellationException（CancellationException 子类）
```
