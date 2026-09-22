# Changelog

## Unreleased

### 🐛 Bug Fixes

- **修复 `tryLoad` 在旧任务取消期间不能立即判定为忙**：此前 `cancelAndJoin()` 或新的 `load` 在等待旧任务清理时，`tryLoad` 会挂起等待清理结束；若是 `cancelAndJoin()`，清理结束后 `tryLoad` 甚至会正常执行。现在这两种情况下 `tryLoad` 都会立即抛出 `FLoader.BusyCancellationException`。
- **避免已取消调用方中断现有加载**：调用方在进入任务替换前检查取消状态，避免已取消的 `load` 调用取消正在运行的加载。

### 🔧 Internal

- **`FMutator._job` 改为 `AtomicReference`**：任务完成后通过 CAS 无锁清理 `_job`，不再占用锁，避免 `tryLoad` 误判为忙，也不会再遗留已完成任务的引用。

## 1.7.1

### 📝 Documentation

- **补充嵌套检测的说明**：嵌套检测基于协程上下文实现，在 `onLoad` 中通过 `runBlocking` 嵌套调用 `load` / `tryLoad` / `cancelAndJoin`，不会被检测到。

### 🔧 Internal

- **移除 `FMutator._job` 上多余的 `@Volatile`**：该字段的读写均在 `Mutex` 保护下进行，无需额外的可见性标注。

## 1.7.0

### ⚠️ Breaking Changes

- **移除 `LoadScope` 和 `onLoadFinish`**：`load` / `tryLoad` 的 block 签名由 `suspend LoadScope.() -> T` 改为 `suspend () -> T`。不再提供 `onLoadFinish` 回调，请在 block 内使用 `try/finally` 处理清理逻辑。
- **`cancel()` 重命名为 `cancelAndJoin()`**：语义更明确，取消当前加载并挂起等待其结束。
- **`tryLoad` 取消行为变更**：加载进行中时抛出新的 `FLoader.BusyCancellationException`（取代原先的裸 `CancellationException`）。它是 `CancellationException` 的子类，若不捕获会静默取消调用方协程。此外，若上一次任务正在取消中，`tryLoad` 也会判定为“忙”并抛出该异常（此前会等待旧任务清理完再放行）。

### ✨ Improvements

- **新增 `FLoader.BusyCancellationException`**：可明确区分“因加载繁忙而取消”的场景，便于调用方按需捕获处理。
- **内部重构**：将互斥、取消、嵌套检测逻辑从 `FLoader` 抽离为独立的组件（`FMutator`、`FMutex`），实现更清晰、更易维护。

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
