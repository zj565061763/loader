# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

`io.github.zj565061763.android:loader` —— 一个小型 Android/Kotlin 库，对外只提供一个 API：`FLoader`。它是基于协程的加载器，串行化加载调用、新加载会取消上一次加载，并对外暴露 `isLoading` 状态流。通过 `com.vanniktech.maven.publish` 发布到 Maven Central。

两个 Gradle 模块：

- `:lib` —— 发布的库本体（`com.sd.lib.loader`）。Android library，minSdk 21，Java 8 target。协程依赖是 **`compileOnly`**，使用方需要自己提供 kotlinx-coroutines。版本号和 POM 元数据在 `lib/gradle.properties`（`VERSION_NAME`）。
- `:app` —— 演示 app（`com.sd.demo.loader`，Compose），同时也是**所有单元测试的存放位置**。因为 `:lib` 自身没有配置测试依赖，库代码的测试写在 `app/src/test/java/com/sd/demo/loader/`。

## 常用命令

```bash
# 跑全部单元测试（主要的验证手段）
./gradlew :app:testDebugUnitTest --console=plain

# 单个测试类 / 单个测试方法（方法名带空格，必须加引号）
./gradlew :app:testDebugUnitTest --tests "com.sd.demo.loader.LoaderTest" --console=plain
./gradlew :app:testDebugUnitTest --tests "com.sd.demo.loader.LoaderTest.test load when success" --console=plain

# 强制重跑（Gradle 会缓存通过的测试，排查偶现问题时需要）
./gradlew :app:testDebugUnitTest --rerun --console=plain

# 只编译不跑测试
./gradlew :app:compileDebugUnitTestKotlin
./gradlew :lib:compileReleaseKotlin

# 构建库的 AAR
./gradlew :lib:assembleRelease
```

Gradle daemon 使用 JDK 21（`gradle/gradle-daemon-jvm.properties`），编译目标是 Java 8。

## 架构

核心代码是 `lib/src/main/java/com/sd/lib/loader/` 下的三个文件，自底向上分层：

**`FMutex`** —— 对 `Mutex` 的封装，额外提供**嵌套调用检测**。每个 `FMutex` 实例创建自己独立的 `CoroutineContext.Key`（`_nestedKey`），`withLock` 会在携带 `NestedElement` 的上下文中执行 action，`checkNested()` 发现当前上下文已持有该 key 就抛异常。key 按实例独立是刻意设计：共享/静态 key 会导致多个 loader 同时使用时误判（1.7.0 修复）。检测基于协程上下文，因此通过 `runBlocking` 嵌套调用检测不到 —— 这个限制已写进公开 KDoc，修改时要保持文档同步。

**`FMutator`**（internal）—— 互斥 + **取消上一个任务**。持有 `_job`（当前正在执行的任务，由 `_jobMutex` 保护），以及一个 `FMutex`（`_mutateMutex`）用于真正的临界区。

- `mutate` —— 取消并 join 上一个 job，把自己装上去，然后执行 block。
- `mutateOrThrow` —— 逻辑相同，但如果上一个 job 存在且尚未完成（包括正在取消中的 job），抛 `BusyException`。
- 两把锁的拆分是必要的：`_jobMutex` 保护 job 交换的簿记逻辑（短、不在用户代码上挂起），`_mutateMutex` 保护用户 block。合并成一把会死锁。
- `_job` 不需要 `@Volatile`，因为所有读写都在 `_jobMutex` 保护下进行。
- `invokeOnCompletion` 里用 `tryLock` 清理 `_job`，是刻意的 best-effort 做法：该回调中不能挂起。

**`FLoader`** —— 公开接口 + `private class LoaderImpl`。并发逻辑全部委托给 `FMutator`，自身只维护 `StateFlow<State>`（`isLoading` 在包裹 `onLoad` 的 `try/finally` 中更新）。`tryLoad` 把 `FMutator.BusyException` 转换成公开的 `FLoader.BusyCancellationException`。

### 修改时必须保持的约束

- `CancellationException` 绝不能被吞成 `Result.failure`。`doLoad` 中显式重新抛出；公开 API `safeRunCatching` 也是出于同样目的存在的。
- `onLoad()` 返回后、包装成 `Result.success` 之前，必须先 `currentCoroutineContext().ensureActive()` —— 在已被取消的协程上完成的 block 不能报告成功。
- `FLoader.BusyCancellationException` 继承自 `CancellationException`，不捕获会静默取消调用方协程。改动 `tryLoad` 抛出的异常类型属于破坏性 API 变更。
- 公开面刻意保持最小：`FLoader`、`FLoader()`、`loadingFlow`、`safeRunCatching`、`FMutex`。`FMutator` 是 `internal`。

## 代码约定

- 缩进 2 空格；注释和 KDoc 用中文。
- 测试方法名用反引号包裹的句子：``fun `test load when loading`() = runTest { ... }``。测试使用 `kotlinx-coroutines-test`（`runTest`、`runCurrent`、`advanceUntilIdle`），flow 断言用 Turbine；验证执行顺序通常是往一个 `container` 字符串/列表里追加标记。
- 每次行为变更都要写 `CHANGELOG.md`（中文，按 ⚠️ Breaking Changes / ✨ Improvements / 🐛 Bug Fixes / 🔧 Internal / 📝 Documentation 分组），破坏性变更要附 `Migration` 代码块。发版时修改 `lib/gradle.properties` 的 `VERSION_NAME`，提交信息就是版本号（例如 `1.7.1`）。
