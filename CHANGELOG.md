# Changelog

## Unreleased

### ⚠️ Breaking Changes

- **`loadingFlow` 改为 `FLoader` 的成员，类型为 `StateFlow<Boolean>`**：此前是返回 `Flow<Boolean>` 的扩展属性。收集的写法不变，但需要删除 `import com.sd.lib.loader.loadingFlow`；当前值可用 `loadingFlow.value` 读取。
- **移除 `FLoader.State` 和 `stateFlow`**：加载状态只有是否加载中一项，请改用 `loadingFlow`。

### 🐛 Bug Fixes

- **修复 `onLoad` 中启动的子协程失败时异常直接从 `load` 抛出**：此前在 `onLoad` 中用当前协程上下文启动子协程（如 `CoroutineScope(currentCoroutineContext()).launch`）时，`onLoad` 一返回 `isLoading` 就变为 `false`，子协程的普通异常会直接从 `load` / `tryLoad` 抛出。现在加载会等子协程结束，子协程的普通异常包装为 `Result.failure`。
- **修复排队加载结束后仍被持有到旧任务清理结束**：此前排队中的 `load` 被新的 `load` 替换或被 `cancelAndJoin()` 取消后，在旧任务清理结束前，它的调用方和 `onLoad` 捕获的对象仍被 Loader 持有。现在排队加载结束后立即释放。

### 📝 Documentation

- **补充 `NonCancellable` 中调用 `load` 的说明**：在 `NonCancellable` 中调用 `load` 只是不会被调用方取消，仍会被 `cancelAndJoin()` 或新的 `load` 取消。
- **补充跨 Loader 取消异常的传播说明**：在 `onLoad` 中调用其他 Loader 时，它被别处取消抛出的 `ManualCancellationException` 或 `ReplacedCancellationException` 会从当前 `load` 原样抛出，不代表当前 Loader 被取消。
- **补充 `tryLoad` 繁忙判定的说明**：上一次加载取消后仍在清理时也算繁忙。
- **修正 `tryLoad` 的说明**：`tryLoad` 不取消也不等待上一次加载，繁忙时立即抛出 `BusyCancellationException`，其余行为与 `load` 相同。
- **补充嵌套检测失效的场景**：除 `runBlocking` 和新线程外，通过新的根协程作用域绕开原上下文时同样无法检测嵌套调用，可能导致死锁。
- **补充多次取消时的异常说明**：加载被多次取消时只保留最先的取消原因，先被新的 `load` 替换再被 `cancelAndJoin()` 取消时抛出的仍是 `ReplacedCancellationException`。
- **补充子协程内忙异常的说明**：在 `onLoad` 内启动的子协程中调用其他 Loader 的 `tryLoad`，忙异常按子协程取消处理，不会传播到当前加载。
- **补充嵌套检测误判的场景**：在 `onLoad` 内启动继承了上下文但换成独立 `Job` 的协程（如 `CoroutineScope(currentCoroutineContext() + Job()).launch`），即使加载已结束，它调用同一 Loader 的 `load` / `tryLoad` / `cancelAndJoin` 仍会被判为嵌套，直接抛出 `IllegalStateException`，`FMutex.withLock` 同理。
- **补充 `isLoading` 变为 `false` 时的说明**：此时加载尚未结束，调用 `load` 或 `cancelAndJoin()` 仍会取消它并丢弃加载结果；需要接着加载时，请在 `load` 返回后再调用。

### Migration

```kotlin
// 1.9.0
import com.sd.lib.loader.loadingFlow
loader.stateFlow.collect { state -> render(state.isLoading) }
val isLoading = loader.stateFlow.value.isLoading

// 新版本
// 删除 import com.sd.lib.loader.loadingFlow
loader.loadingFlow.collect { isLoading -> render(isLoading) }
val isLoading = loader.loadingFlow.value
```

## 1.9.0

### 🐛 Bug Fixes

- **修复连续调用 `load` 时排队中的旧加载仍会先执行**：此前一个 `load` 在等待旧任务清理时，即使又有更新的 `load` 进入，它仍会在旧任务结束后开始执行 `onLoad`，之后才被取消，最新的加载还要多等它清理一次。现在更新的 `load` 进入时会立即取消它，它抛出 `FLoader.ReplacedCancellationException`，不会执行 `onLoad`。如果更新的 `load` 随后被其调用方取消，两者都不会执行。

### 📝 Documentation

- **修正调用方取消时的异常说明**：`load` 被调用方取消时抛出调用方的取消原因，不一定是普通的 `CancellationException`。在外层 Loader 的加载回调中调用 `load` 时，外层被取消，内层抛出的是外层的 `ManualCancellationException` 或 `ReplacedCancellationException`，不能据此判断是内层 Loader 发起的取消。
- **补充 `withTimeout` 超时说明**：`onLoad` 中 `withTimeout` 超时抛出的 `TimeoutCancellationException` 属于 `CancellationException`，`load` 会原样抛出，不返回 `Result.failure`。需要失败结果时改用 `withTimeoutOrNull`，或转换为普通异常。
- **补充在 `flow {}` 中使用的限制**：在 `flow {}` 中，`load` / `tryLoad` 的 `onLoad` 内不能调用 `emit`，否则返回 `Result.failure`。`FMutex.withLock` 的 `action` 内同样不能调用 `emit`。请改用 `channelFlow`，或在回调外 `emit`。

## 1.8.1

### 🐛 Bug Fixes

- **修复已取消调用方的 `cancelAndJoin()` 可能不取消加载**：此前调用方已取消时（如在 `finally` 中调用），若恰逢新的 `load` 正在等待旧任务清理，`cancelAndJoin()` 会直接抛出 `CancellationException`，新加载照常运行。现在与 `Job.cancelAndJoin()` 一致，调用后一定发起取消，只是不保证等待完成。

### ✨ Improvements

- **`cancelAndJoin()` 一并取消等待旧任务清理的 `load`**：这类 `load` 会立即抛出 `CancellationException`，不再等旧任务清理结束。`cancelAndJoin()` 之后发起的加载不受影响。
- **新增 `FLoader.ManualCancellationException` 和 `FLoader.ReplacedCancellationException`**：加载被 `cancelAndJoin()` 取消时抛出前者，被新的 `load` 取消时抛出后者，便于区分取消来源。两者都是 `CancellationException` 的子类，原有捕获逻辑不受影响。

## 1.8.0

### 🐛 Bug Fixes

- **修复 `tryLoad` 在旧任务取消期间不能立即判定为忙**：此前 `cancelAndJoin()` 或新的 `load` 在等待旧任务清理时，`tryLoad` 会挂起等待清理结束；若是 `cancelAndJoin()`，清理结束后 `tryLoad` 甚至会正常执行。现在这两种情况下 `tryLoad` 都会立即抛出 `FLoader.BusyCancellationException`。
- **避免已取消调用方中断现有加载**：调用方在进入任务替换前检查取消状态，避免已取消的 `load` 调用取消正在运行的加载。
- **避免空闲取消造成短暂忙状态**：空闲时调用 `cancelAndJoin()` 会直接返回，不再与并发的 `tryLoad` 竞争任务锁。

### ✨ Improvements

- **为忙异常增加明确消息**：`FLoader.BusyCancellationException` 的 message 现在是 `Loader is busy`，便于日志排查。

### 📝 Documentation

- **补充公开 API 和取消传播说明**：说明 `load` 被替换时的取消行为、跨 Loader 的忙异常传播，以及 `FMutex` 嵌套检测的限制。
- **补充协程依赖说明**：README 说明使用方需要自行添加兼容版本的 `kotlinx-coroutines`。

## 1.7.1

### 📝 Documentation

- **补充嵌套检测的说明**：嵌套检测基于协程上下文实现，在 `onLoad` 中通过 `runBlocking` 嵌套调用 `load` / `tryLoad` / `cancelAndJoin`，不会被检测到。

## 1.7.0

### ⚠️ Breaking Changes

- **移除 `LoadScope` 和 `onLoadFinish`**：`load` / `tryLoad` 的 block 签名由 `suspend LoadScope.() -> T` 改为 `suspend () -> T`。不再提供 `onLoadFinish` 回调，请在 block 内使用 `try/finally` 处理清理逻辑。
- **`cancel()` 重命名为 `cancelAndJoin()`**：语义更明确，取消当前加载并挂起等待其结束。
- **`tryLoad` 取消行为变更**：加载进行中时抛出新的 `FLoader.BusyCancellationException`（取代原先的裸 `CancellationException`）。它是 `CancellationException` 的子类，若不捕获会静默取消调用方协程。此外，若上一次任务正在取消中，`tryLoad` 也会判定为“忙”并抛出该异常（此前会等待旧任务清理完再放行）。

### ✨ Improvements

- **新增 `FLoader.BusyCancellationException`**：可明确区分“因加载繁忙而取消”的场景，便于调用方按需捕获处理。

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
