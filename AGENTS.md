# AGENTS.md

## 项目概览

- 本项目是 Android/Kotlin 协程加载库，Maven 坐标为 `io.github.zj565061763.android:loader`
- `:lib` 是发布的 Android library，源码包为 `com.sd.lib.loader`，通过 `com.vanniktech.maven.publish` 发布到 Maven Central
- `:app` 是 Compose 示例应用，同时承载库的全部单元测试；测试位于 `app/src/test/java/com/sd/demo/loader/`
- `:lib` 中的协程依赖使用 `compileOnly`，使用方必须自行提供 `kotlinx-coroutines`
- `minSdk` 为 21，源码和 Kotlin 字节码目标为 Java 8；Gradle daemon 使用 JDK 21
- 依赖及插件版本以 `gradle/libs.versions.toml` 为准，发布版本和 POM 元数据以 `lib/gradle.properties` 为准

## 常用命令

```bash
# 运行主要验证项
./gradlew :app:testDebugUnitTest --console=plain

# 运行单个测试类或方法；方法名包含空格时必须加引号
./gradlew :app:testDebugUnitTest --tests "com.sd.demo.loader.LoaderTest" --console=plain
./gradlew :app:testDebugUnitTest --tests "com.sd.demo.loader.LoaderTest.test load when success" --console=plain

# 排查偶现问题时绕过 Gradle 测试缓存
./gradlew :app:testDebugUnitTest --rerun --console=plain

# 只编译测试或库代码
./gradlew :app:compileDebugUnitTestKotlin
./gradlew :lib:compileReleaseKotlin

# 构建发布 AAR
./gradlew :lib:assembleRelease
```

## 代码结构与公开 API

核心代码位于 `lib/src/main/java/com/sd/lib/loader/`：

- `FLoader.kt`：公开的 `FLoader` 接口、工厂函数 `FLoader()`、`loadingFlow` 扩展和 `safeRunCatching`，具体实现是私有的 `LoaderImpl`
- `FMutator.kt`：内部并发协调器，负责串行执行、取消旧任务以及忙状态判断，不属于公开 API
- `FMutex.kt`：公开的互斥封装，在普通 `Mutex` 基础上增加同一实例的嵌套调用检测

公开面应保持精简。修改 `FLoader`、`FLoader()`、`FLoader.State`、`FLoader.ManualCancellationException`、`FLoader.ReplacedCancellationException`、`FLoader.BusyCancellationException`、`loadingFlow`、`safeRunCatching` 或 `FMutex` 时，应按公开 API 兼容性审视改动。

## 并发语义与不可破坏的约束

### `FLoader`

- `load` 会取消并等待上一次加载结束，然后串行执行新加载
- 新的 `load` 进入时立即取消上一个任务，包括仍在等待旧任务清理的；排队中的旧 `load` 抛出 `ReplacedCancellationException`，不会执行 `onLoad`
- 被 `cancelAndJoin` 取消的加载抛出 `FLoader.ManualCancellationException`，被新的 `load` 取消的旧加载（包括 `tryLoad` 发起的）抛出 `FLoader.ReplacedCancellationException`，被调用方取消时抛出调用方的取消原因
- 取消原因会传给子任务：在外层 Loader 的 `onLoad` 中调用内层 Loader 时，外层被取消，内层抛出的是外层的 `ManualCancellationException` 或 `ReplacedCancellationException`
- 这些公开异常直接作为取消原因传给 `Job.cancel`，`onLoad` 内收到的也是同一类型；不能改为在 `load`/`tryLoad` 出口转换
- 加载被多次取消时只保留最先的取消原因：先被新的 `load` 替换再被 `cancelAndJoin` 取消，抛出的仍是 `ReplacedCancellationException`
- `onLoad` 内启动的子协程中抛出的 `BusyCancellationException` 等取消异常按子协程取消处理，不会传播到当前加载，这是协程标准语义
- `tryLoad` 在已有任务尚未完成时立即抛出 `FLoader.BusyCancellationException`，包括旧任务正在取消但尚未完成的阶段；它不能取消正在执行的任务
- `ManualCancellationException`、`ReplacedCancellationException` 和 `BusyCancellationException` 都是 `CancellationException` 的子类，调用方若不捕获，它们会按协程取消语义传播；改变异常类型属于破坏性 API 变更
- `cancelAndJoin` 会取消当前加载并等待其清理结束
- `cancelAndJoin` 只取消调用时已进入 `load`/`tryLoad` 的任务，包括正在等待旧任务清理的任务；之后发起的加载不受影响
- 调用方已取消时，`cancelAndJoin` 仍必须发起取消，只是不保证等待完成，与 `Job.cancelAndJoin()` 一致
- 已取消的调用方不能取消其他加载：`mutate` 进入时先检查 `ensureActive`，再取消上一个任务
- `doLoad` 只把普通异常转换为 `Result.failure`；`CancellationException` 必须重新抛出，不能被包装或吞掉。公开的 `safeRunCatching` 也遵循相同规则
- `doLoad` 必须用 `coroutineScope` 包裹 `onLoad`：`onLoad` 用当前上下文启动的子协程挂在这个 scope 上，否则子协程的普通异常会绕过 `Result.failure`，`isLoading` 也会在子协程结束前变为 `false`
- `onLoad` 返回后、创建 `Result.success` 前再次调用 `currentCoroutineContext().ensureActive()`，作为防御性检查
- `isLoading` 在调用 `onLoad` 前设为 `true`，并在 `finally` 中恢复为 `false`。重新加载时会依次更新为 `false`、`true`，但 `StateFlow` 可能合并快速更新，收集者不保证收到完整序列

### `FMutator`

- 取消、替换和忙状态的异常工厂必须显式传入，不提供默认值
- `_job` 是最近进入的任务，可能还在等待；`_runningJob` 是已开始执行 block 且尚未结束的任务，任何时刻最多一个
- 两个字段只在 `_lock` 内读写；锁内不能挂起，`cancel`、`join` 都放在锁外
- `mutate` 进入时在锁内把自己设为 `_job`，锁外取消上一个 `_job`，再等待进入时读到的 `_runningJob` 结束
- `mutate` 不直接取消 `_runningJob`，依赖链式取消：每个成为 `_job` 的任务都会同步取消它读到的上一个 `_job`，一直传递到 `_runningJob`；`prevJob?.cancel` 必须紧跟登记且中间不能有挂起点，否则排队任务被调用方取消后 `_runningJob` 可能无人取消
- 只有仍是 `_job` 的任务才能开始执行，并在同一次加锁中设为 `_runningJob`；否则说明有更新的任务进入，以 `newReplaceCause()` 取消自己。这条保证串行执行，不能去掉
- 排队任务被取消后可能先于 `_runningJob` 结束，所以判断忙和等待时必须同时看两个字段，不能只看 `_job`
- `mutateOrThrow` 进入时先检查 `ensureActive` 再登记任务，避免已取消的调用方让空闲的 Loader 短暂变忙
- `mutateOrThrow` 在锁内判断：`_job` 或 `_runningJob` 未完成即为忙，否则把自己同时设为两者；它不取消也不等待任何任务
- `doMutate` 在 `FMutex.withLock` 内、执行 block 前再次检查 `ensureActive`，拦住 `withContext` 安装线程上下文期间发生的取消
- 任务结束时在锁内清空等于自己的字段
- `cancelAndJoin` 在锁内读取两个字段，全部取消后再一起等待
- `cancelAndJoin` 必须先取消 `_job` 再取消 `_runningJob`，顺序不能调换：运行任务在 `Unconfined` 上时会在取消时内联结束，先取消它会让尚未取消的排队任务立即开始执行
- `cancelAndJoin` 不能循环重试直到没有任务，否则单线程调度器上可能忙等卡死，也会误取消之后发起的加载
- `_mutateMutex` 保护可能挂起的用户 block，并提供嵌套检测

### `FMutex` 与嵌套调用

- 每个 `FMutex` 实例拥有独立的 `CoroutineContext.Key`；不同实例可以嵌套，同一实例嵌套会抛出消息为 `Nested invoke` 的 `IllegalStateException`
- key 必须按实例隔离，不能改为共享或静态 key，否则多个 loader 相互嵌套时会被误判
- 嵌套检测依赖协程上下文。在 `withLock`/`onLoad` 内通过 `runBlocking`、新线程等方式绕开原上下文时无法检测，可能导致自锁；公开 KDoc 必须持续说明这一限制
- 嵌套检测会在锁内的协程上下文中加入元素，`flow {}` 中在 `withLock`/`onLoad` 内 `emit` 会违反 Flow 的上下文约束；公开 KDoc 必须持续说明这一限制
- `FLoader.load`、`tryLoad` 和 `cancelAndJoin` 都必须在进入任务簿记或锁等待前执行嵌套检查

## 测试约定

- 库的 JVM 单元测试统一放在 `:app`，因为 `:lib` 没有配置测试依赖
- `:app` 单元测试通过 `friendPaths` 访问同一构建变体的 `:lib` 内部成员
- `MutatorTest` 显式抑制 `INVISIBLE_REFERENCE` 和 `INVISIBLE_MEMBER`，兼容 IDE 未识别跨模块友元关系的分析
- 测试使用 `kotlinx-coroutines-test` 的 `runTest`、`runCurrent`、`advanceUntilIdle`，Flow 断言使用 Turbine
- 测试方法名使用反引号包裹的英文句子，例如 ``fun `test load when loading`() = runTest { ... }``
- 跨协程边界验证异常原始实例时使用带业务字段的异常，避免调试模式的堆栈恢复复制异常
- 验证并发执行顺序时，通常向字符串或列表形式的 `container` 追加标记
- 修改并发逻辑时至少覆盖成功、普通异常、取消、忙状态、嵌套调用、锁释放和 `isLoading`/Flow 状态序列
- 引用释放测试在 `runTest` 退出后等待真实 GC，并保持 loader 存活且不再发起加载

| 测试类 | 覆盖范围 |
|---|---|
| `LoaderTest` | 加载结果、取消（含先被替换再被 `cancelAndJoin` 取消时保留替换原因）、排队（含旧任务结束后恢复前被 `cancelAndJoin` 取消）、多线程（含 `onLoad` 抛普通异常）、嵌套（含新根 scope 绕开检测后 `load` 替换外层、`tryLoad` 判忙穿透外层）、Unconfined 下运行中和排队中调用方 `finally` 内联重入（含 `tryLoad` 判忙）、`loadingFlow`（含普通异常、排队加载被替换和 `tryLoad` 被替换）和 `stateFlow` 序列 |
| `LoaderCallbackTest` | `load` 与 `tryLoad` 的异常包装、子协程生命周期（含回调抛取消异常时子协程收到同一原因并等待清理，以及子协程内其他 Loader 的忙异常不传播）、线程上下文安装期间的取消、回调内的嵌套调用和 Flow 上下文约束 |
| `LoaderQueuedCleanupTest` | `load` 与 `tryLoad` 发起的任务在排队调用方取消或超时后仍保持忙状态和清理等待，以及之后的新 `load` 等待清理后执行 |
| `LoaderReferenceTest` | `load` 与 `tryLoad` 在成功、普通异常和取消异常退出后释放结果及异常数据、成功后释放回调闭包捕获的对象，以及排队加载取消后的异常数据释放 |
| `MutatorTest` | 已取消调用方的任务登记、已登记 `load` 与 `tryLoad` 的取消和替换、上下文探针的暂停位置，新任务登记后尚未发起取消时的排队任务替换、手动取消和忙状态，以及 `cancelAndJoin` 的取消顺序 |
| `MutexTest` | 互斥、锁释放（含 `action` 抛出取消异常，以及吞掉取消后正常返回时仍抛出）、嵌套（含子协程，以及新根 scope 绕开检测后等待锁）和 Flow 上下文约束 |

## 编码与发布约定

- Kotlin 代码使用 2 空格缩进，注释和 KDoc 使用中文
- 不要无意扩大公开 API；新增或修改公开 API 时提供简洁 KDoc
- 每次行为变化都更新 `CHANGELOG.md`，使用现有的 `⚠️ Breaking Changes`、`✨ Improvements`、`🐛 Bug Fixes`、`📝 Documentation` 分类；不对使用方产生影响的内部改动不写入 changelog
- 破坏性变更在 changelog 中附带 `Migration` 代码示例
- 发版时更新 `lib/gradle.properties` 中的 `VERSION_NAME`；版本发布提交标题使用版本号，例如 `1.7.1`
