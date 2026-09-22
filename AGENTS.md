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

公开面应保持精简。修改 `FLoader`、`FLoader()`、`FLoader.State`、`FLoader.BusyCancellationException`、`loadingFlow`、`safeRunCatching` 或 `FMutex` 时，应按公开 API 兼容性审视改动。

## 并发语义与不可破坏的约束

### `FLoader`

- `load` 会取消并等待上一次加载结束，然后串行执行新加载
- `tryLoad` 在已有任务尚未完成时立即抛出 `FLoader.BusyCancellationException`，包括旧任务正在取消但尚未完成的阶段；它不能取消正在执行的任务
- `BusyCancellationException` 是 `CancellationException` 的子类，调用方若不捕获，它会按协程取消语义传播；改变异常类型属于破坏性 API 变更
- `cancelAndJoin` 会取消当前加载并等待其清理结束
- `doLoad` 只把普通异常转换为 `Result.failure`；`CancellationException` 必须重新抛出，不能被包装或吞掉。公开的 `safeRunCatching` 也遵循相同规则
- `onLoad` 返回后、创建 `Result.success` 前必须调用 `currentCoroutineContext().ensureActive()`，避免已取消的协程错误地报告成功
- `isLoading` 在调用 `onLoad` 前设为 `true`，并在 `finally` 中恢复为 `false`。重新加载时，旧任务清理和新任务开始之间会依次发出 `false`、`true`

### `FMutator`

- `_jobMutex` 仅保护当前 `Job` 的读取、替换和清理，临界区应保持短小；`_mutateMutex` 负责保护可能挂起的用户 block
- 两把锁不可合并，否则取消并等待旧任务时可能形成死锁
- `_job` 是 `AtomicReference`：锁内负责读取和替换，任务完成回调通过 `compareAndSet(mutateJob, null)` 无锁清理；修改这段逻辑时需同时验证完成、取消和任务替换的竞态
- `tryLoad` 通过 `_jobMutex.tryLock()` 获取锁，失败即判定为忙，不能改为先检查再挂起加锁，否则检查与加锁之间仍可能挂起等待旧任务清理；因此完成回调不能持有 `_jobMutex`，锁只应在取消或替换任务时被持有

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
- 每次行为变化都更新 `CHANGELOG.md`，使用现有的 `⚠️ Breaking Changes`、`✨ Improvements`、`🐛 Bug Fixes`、`🔧 Internal`、`📝 Documentation` 分类
- 破坏性变更在 changelog 中附带 `Migration` 代码示例
- 发版时更新 `lib/gradle.properties` 中的 `VERSION_NAME`；版本发布提交标题使用版本号，例如 `1.7.1`
