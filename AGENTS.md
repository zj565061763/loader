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

公开面应保持精简。修改 `FLoader`、`FLoader()`、`FLoader.State`、`FLoader.BusyCancellationException`、`FLoader.ReplacedCancellationException`、`FLoader.ManualCancellationException`、`loadingFlow`、`safeRunCatching` 或 `FMutex` 时，应按公开 API 兼容性审视改动。

## 并发语义与不可破坏的约束

### `FLoader`

- `load` 会取消并等待上一次加载结束，然后串行执行新加载
- 被新的 `load` 取消的旧加载（包括 `tryLoad` 发起的）抛出 `FLoader.ReplacedCancellationException`，被 `cancelAndJoin` 取消时抛出 `FLoader.ManualCancellationException`，被调用方取消时抛出普通的 `CancellationException`
- 这些公开异常直接作为取消原因传给 `Job.cancel`，`onLoad` 内收到的也是同一类型；不能改为在 `load`/`tryLoad` 出口转换
- `tryLoad` 在已有任务尚未完成时立即抛出 `FLoader.BusyCancellationException`，包括旧任务正在取消但尚未完成的阶段；它不能取消正在执行的任务
- `BusyCancellationException`、`ReplacedCancellationException` 和 `ManualCancellationException` 都是 `CancellationException` 的子类，调用方若不捕获，它们会按协程取消语义传播；改变异常类型属于破坏性 API 变更
- `cancelAndJoin` 会取消当前加载并等待其清理结束
- `cancelAndJoin` 只取消调用时已进入 `load`/`tryLoad` 的任务，包括正在等待旧任务清理的任务；之后发起的加载不受影响
- 调用方已取消时，`cancelAndJoin` 仍必须发起取消，只是不保证等待完成，与 `Job.cancelAndJoin()` 一致
- 已取消的调用方不能取消正在运行的加载：`mutate` 在加锁前和加锁后都要检查 `ensureActive`，然后才能取消旧任务
- `doLoad` 只把普通异常转换为 `Result.failure`；`CancellationException` 必须重新抛出，不能被包装或吞掉。公开的 `safeRunCatching` 也遵循相同规则
- `onLoad` 返回后、创建 `Result.success` 前必须调用 `currentCoroutineContext().ensureActive()`，避免已取消的协程错误地报告成功
- `isLoading` 在调用 `onLoad` 前设为 `true`，并在 `finally` 中恢复为 `false`。重新加载时会依次更新为 `false`、`true`，但 `StateFlow` 可能合并快速更新，收集者不保证收到完整序列

### `FMutator`

- `_jobMutex` 保护当前 `Job` 的读取和替换，以及替换时取消并等待旧任务的过程；`_mutateMutex` 负责保护可能挂起的用户 block
- 两把锁不可合并，否则取消并等待旧任务时可能形成死锁
- `_job` 是 `AtomicReference`：替换在锁内进行，`cancelAndJoin` 无锁读取，任务完成回调通过 `compareAndSet(mutateJob, null)` 无锁清理；修改这段逻辑时需同时验证完成、取消和任务替换的竞态
- `_preparingJobs` 记录已进入但尚未设置为 `_job` 的任务：进入 `mutate` 时加入，设置为 `_job` 后或任务结束时移除
- `cancelAndJoin` 不持有 `_jobMutex`：先收集 `_preparingJobs` 和 `_job`，全部取消后再一起等待
- `cancelAndJoin` 不能循环重试直到没有任务，否则单线程调度器上可能忙等卡死，也会误取消之后发起的加载
- `cancelAndJoin` 先读 `_preparingJobs` 再读 `_job`，`mutate` 先设置 `_job` 再移出 `_preparingJobs`；两边顺序不可调换，否则可能漏掉正在替换任务的加载
- `tryLoad` 通过 `_jobMutex.tryLock()` 获取锁，失败即判定为忙，不能改为先检查再挂起加锁，否则检查与加锁之间仍可能挂起等待旧任务清理；因此完成回调不能持有 `_jobMutex`，锁只应在替换任务时被持有

### `FMutex` 与嵌套调用

- 每个 `FMutex` 实例拥有独立的 `CoroutineContext.Key`；不同实例可以嵌套，同一实例嵌套会抛出消息为 `Nested invoke` 的 `IllegalStateException`
- key 必须按实例隔离，不能改为共享或静态 key，否则多个 loader 相互嵌套时会被误判
- 嵌套检测依赖协程上下文。在 `withLock`/`onLoad` 内通过 `runBlocking`、新线程等方式绕开原上下文时无法检测，可能导致自锁；公开 KDoc 必须持续说明这一限制
- `FLoader.load`、`tryLoad` 和 `cancelAndJoin` 都必须在进入任务簿记或锁等待前执行嵌套检查

## 测试约定

- 库的 JVM 单元测试统一放在 `:app`，因为 `:lib` 没有配置测试依赖
- 测试使用 `kotlinx-coroutines-test` 的 `runTest`、`runCurrent`、`advanceUntilIdle`，Flow 断言使用 Turbine
- 测试方法名使用反引号包裹的英文句子，例如 ``fun `test load when loading`() = runTest { ... }``
- 验证并发执行顺序时，通常向字符串或列表形式的 `container` 追加标记
- 修改并发逻辑时至少覆盖成功、普通异常、取消、忙状态、嵌套调用、锁释放和 `isLoading`/Flow 状态序列

## 编码与发布约定

- Kotlin 代码使用 2 空格缩进，注释和 KDoc 使用中文
- 不要无意扩大公开 API；新增或修改公开 API 时提供简洁 KDoc
- 每次行为变化都更新 `CHANGELOG.md`，使用现有的 `⚠️ Breaking Changes`、`✨ Improvements`、`🐛 Bug Fixes`、`📝 Documentation` 分类；不对使用方产生影响的内部改动不写入 changelog
- 破坏性变更在 changelog 中附带 `Migration` 代码示例
- 发版时更新 `lib/gradle.properties` 中的 `VERSION_NAME`；版本发布提交标题使用版本号，例如 `1.7.1`
