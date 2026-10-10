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

- `FLoader.kt`：公开的 `FLoader` 接口、工厂函数 `FLoader()` 和 `safeRunCatching`，具体实现是私有的 `LoaderImpl`
- `FMutator.kt`：内部并发协调器，负责串行执行、取消旧任务以及忙状态判断，不属于公开 API
- `FMutex.kt`：公开的互斥封装，在普通 `Mutex` 基础上增加同一实例的嵌套调用检测

公开面应保持精简。修改 `FLoader`、`FLoader()`、`FLoader.ManualCancellationException`、`FLoader.ReplacedCancellationException`、`FLoader.BusyCancellationException`、`safeRunCatching` 或 `FMutex` 时，应按公开 API 兼容性审视改动。

## 并发语义与不可破坏的约束

### `FLoader`

- `load` 会取消并等待上一次加载结束，然后串行执行新加载
- 新的 `load` 进入时立即取消上一个任务，包括仍在等待旧任务清理的；排队中的旧 `load` 抛出 `ReplacedCancellationException`，不会执行 `onLoad`
- 被 `cancelAndJoin` 取消的加载抛出 `FLoader.ManualCancellationException`，被新的 `load` 取消的旧加载（包括 `tryLoad` 发起的）抛出 `FLoader.ReplacedCancellationException`，被调用方取消时抛出调用方的取消原因
- 取消原因会传给子任务：在外层 Loader 的 `onLoad` 中调用内层 Loader 时，外层被取消，内层抛出的是外层的 `ManualCancellationException` 或 `ReplacedCancellationException`
- 这些公开异常直接作为取消原因传给 `Job.cancel`，`onLoad` 内收到的也是同一类型；不能改为在 `load`/`tryLoad` 出口转换
- 加载被多次取消时只保留最先的取消原因：先被新的 `load` 替换再被 `cancelAndJoin` 取消，抛出的仍是 `ReplacedCancellationException`；先被替换或被 `cancelAndJoin` 取消再被调用方取消，抛出的仍是前者的原因；`onLoad` 捕获取消后抛出其他取消异常也不改变取消原因
- 调用方取消后旧任务仍在清理时，新的 `load` 必须等待清理结束，`tryLoad` 判忙，旧调用方收到的仍是自己的取消原因
- `onLoad` 内启动的子协程中抛出的 `BusyCancellationException` 等取消异常按子协程取消处理，不会传播到当前加载，这是协程标准语义
- `tryLoad` 在已有任务尚未完成时立即抛出 `FLoader.BusyCancellationException`，包括旧任务正在取消但尚未完成的阶段；它不能取消正在执行的任务
- `ManualCancellationException`、`ReplacedCancellationException` 和 `BusyCancellationException` 都是 `CancellationException` 的子类，调用方若不捕获，它们会按协程取消语义传播；改变异常类型属于破坏性 API 变更
- `cancelAndJoin` 会取消当前加载并等待其清理结束
- `cancelAndJoin` 只取消调用时已进入 `load`/`tryLoad` 的任务，包括正在等待旧任务清理的任务；之后发起的加载不受影响
- 调用方已取消时，`cancelAndJoin` 仍必须发起取消，只是不保证等待完成，与 `Job.cancelAndJoin()` 一致
- 已取消的调用方不能取消其他加载，也不能妨碍之后的 `load` 替换运行中的加载：`mutate` 进入时先检查 `ensureActive`，再登记并取消上一个任务
- `doLoad` 只把普通异常转换为 `Result.failure`；`CancellationException` 必须重新抛出，不能被包装或吞掉。公开的 `safeRunCatching` 也遵循相同规则
- `doLoad` 必须用 `coroutineScope` 包裹 `onLoad`：`onLoad` 用当前上下文启动的子协程挂在这个 scope 上，否则子协程的普通异常会绕过 `Result.failure`
- `isBusyFlow` 直接返回 `FMutator.isBusyFlow`，对外只读，使用方不能强转成 `MutableStateFlow` 修改状态
- `isBusyFlow` 在 `load` 登记或 `tryLoad` 被接受时变为 `true`，所有任务结束后才恢复 `false`，排队中被替换的任务除外；排队等待和重新加载期间一直为 `true`
- 排队中被新的 `load` 替换的任务不算忙：其他任务都结束后它的调用方可能尚未返回，此时 `isBusy` 为 `false`，`tryLoad` 不判忙；公开 KDoc 必须持续说明这一限制
- `StateFlow` 可能合并快速更新，收集者不保证收到完整序列
- Unconfined 收集者会在 `isBusyFlow` 的同步调用中内联执行：变为 `true` 时加载已登记但尚未进入 `onLoad`，内联取消或替换后 `onLoad` 不会执行
- `Dispatchers.Main.immediate` 上的收集者在主线程同步 `isBusyFlow` 时同样内联执行，它是 `viewModelScope` 和 `lifecycleScope` 的默认调度器
- 调用方自己也在 Unconfined 或 `Main.immediate` 上内联运行时，收集者不内联执行，要等调用方挂起或结束才运行：变为 `true` 时可能已进入 `onLoad`
- 主线程上直接 `viewModelScope.launch { loader.load {} }` 就属于上一条
- `isBusyFlow` 只是状态通知，可能短暂落后于 `isBusy`，不能用来先判断再 `tryLoad`；公开 KDoc 必须持续说明这一限制
- `isBusy` 直接返回 `FMutator.isBusy()`，与 `tryLoad` 的判忙是同一个判断
- `isBusy` 不做嵌套检查，在 `onLoad` 内调用返回 `true`
- `isBusy` 只是调用时刻的快照，不能用来先判断再 `tryLoad`；公开 KDoc 必须持续说明这一限制

### `FMutator`

- 取消、替换和忙状态的异常工厂必须显式传入，不提供默认值
- `_job` 是最近进入的任务，可能还在等待；`_runningJob` 是已开始执行 block 且尚未结束的任务，任何时刻最多一个
- 两个字段只在 `_lock` 内读写；锁内不能挂起，`cancel`、`join` 都放在锁外
- `mutate` 进入时在锁内把自己设为 `_job`，锁外取消上一个 `_job`，再等待进入时读到的 `_runningJob` 结束
- `mutate` 只等待 `_runningJob`，不等待被它取消的排队任务退出，否则排队任务所在调度器繁忙时会拖住新的 `load`
- `mutate` 等待 `_runningJob` 期间不能再引用进入时读到的上一个 `_job`：挂起点之后还用到的变量会一直被持有，上一个任务结束后它和调用方的回调闭包要等旧任务清理完才能释放
- `mutate` 的 `ensureActive` 必须在登记 `_job` 之前：已取消的调用方登记后直接退出，运行中的任务没被取消又不再是 `_job`，之后的 `load` 取消不到它，会一直等待
- `mutate` 不直接取消 `_runningJob`，依赖链式取消：每个成为 `_job` 的任务都会同步取消它读到的上一个 `_job`，一直传递到 `_runningJob`；`prevJob?.cancel` 必须紧跟登记且中间不能有挂起点，否则排队任务被调用方取消后 `_runningJob` 可能无人取消
- 只有仍是 `_job` 的任务才能开始执行，并在同一次加锁中设为 `_runningJob`；否则说明有更新的任务进入，以 `newReplaceCause()` 取消自己。这条保证串行执行，不能去掉
- 顶替判断与是否等待过 `_runningJob` 无关：进入时没有运行任务的任务也可能在登记后被更新的任务顶替，漏判会让它执行 block
- 被顶替的任务不能登记为 `_runningJob`，否则它退出时会清空 `_runningJob`，盖掉已开始执行的新任务的登记
- 排队任务被取消后可能先于 `_runningJob` 结束，所以判断忙和等待时必须同时看两个字段，不能只看 `_job`
- `mutateOrThrow` 进入时先检查 `ensureActive` 再登记任务，避免已取消的调用方让空闲的 Loader 短暂变忙
- `isBusy` 在锁内判断：`_job` 或 `_runningJob` 未完成即为忙
- `mutateOrThrow` 在锁内调用 `isBusy` 判忙，不忙则把自己同时设为两者；它不取消也不等待任何任务
- 判忙的条件只写在 `isBusy` 里，`mutateOrThrow` 不能另写一份
- `isBusy` 只是调用时刻的快照，不能用来先判断再 `mutateOrThrow`：判忙与登记必须在同一次加锁中完成
- `isBusyFlow` 的值只从 `isBusy` 读出，由 `syncBusyFlow` 同步，通过 `asStateFlow()` 对外只读
- `mutate` 登记并取消上一个任务之后、`mutateOrThrow` 登记之后、任务结束清空登记之后各同步一次
- `syncBusyFlow` 必须在 `_lock` 外赋值：Unconfined 或 `Main.immediate` 上的收集者会在赋值调用中内联执行，锁内不能运行外部代码
- `syncBusyFlow` 赋值后必须复查 `isBusy`，变了就重新同步：只赋值一次时，一个线程读到的旧值可能晚于另一个线程的新值赋上去，状态流会停在过期的值上
- 任务结束时的同步在结束回调里执行，此时任务已完成、调用方尚未恢复：单线程下 `load` 返回时状态流已恢复 `false`
- 判忙看任务是否完成，不看是否活动：被 `cancelAndJoin` 或调用方取消但尚未退出的排队任务也算忙
- 被顶替的排队任务不再是 `_job`，也不会再执行 block，不计入判忙：其他任务都结束后它可能尚未退出，此时不算忙
- 判忙也不看字段是否为 `null`：任务完成到清空登记之间，字段仍指向已完成的任务，此时不算忙
- 判忙的 `mutateOrThrow` 不改动两个字段，否则之后的 `load` 取消不到运行中的任务，会一直等待
- `doMutate` 在 `FMutex.withLock` 内、执行 block 前再次检查 `ensureActive`，拦住 `withContext` 安装线程上下文期间发生的取消
- 任务结束时在锁内清空等于自己的字段
- `cancelAndJoin` 在锁内读取两个字段，全部取消后再逐个等待：先等 `_job`，再等 `_runningJob`
- `cancelAndJoin` 等 `_runningJob` 时不能再引用 `_job`，原因同 `mutate`；不能把两个任务放进集合后一起等待
- `cancelAndJoin` 等 `_job` 期间仍引用 `_runningJob`：运行任务先结束时，它和调用方的回调闭包要等被取消的排队任务退出才能释放
- 上述持有是已接受的限制，审查时不作为 bug 上报：持有时间只有排队任务等到一次调度那么长
- `cancelAndJoin` 要取消和等待的任务必须在发起取消前一次读出，之后不能再读两个字段：`Unconfined` 下取消会内联执行调用方的 `finally`，其中发起的加载不属于本次取消，不能被取消或等待
- `cancelAndJoin` 不能只等待 `_runningJob`：被取消的排队任务可能晚于它退出，提前返回后 `tryLoad` 仍会判忙
- `cancelAndJoin` 必须先取消 `_job` 再取消 `_runningJob`，顺序不能调换：运行任务在 `Unconfined` 上时会在取消时内联结束，先取消它会让尚未取消的排队任务立即开始执行
- `cancelAndJoin` 不能循环重试直到没有任务，否则单线程调度器上可能忙等卡死，也会误取消之后发起的加载
- `_mutateMutex` 保护可能挂起的用户 block，并提供嵌套检测

### `FMutex` 与嵌套调用

- 每个 `FMutex` 实例拥有独立的 `CoroutineContext.Key`；不同实例可以嵌套，同一实例嵌套会抛出消息为 `Nested invoke` 的 `IllegalStateException`
- key 必须按实例隔离，不能改为共享或静态 key，否则多个 loader 相互嵌套时会被误判
- 嵌套检测依赖协程上下文。在 `withLock`/`onLoad` 内通过 `runBlocking`、新线程等方式绕开原上下文时无法检测，可能导致自锁；公开 KDoc 必须持续说明这一限制
- 嵌套检测会在锁内的协程上下文中加入元素，`flow {}` 中在 `withLock`/`onLoad` 内 `emit` 会违反 Flow 的上下文约束；公开 KDoc 必须持续说明这一限制
- 嵌套标记随协程上下文传递：在 `withLock`/`onLoad` 内继承上下文但换成独立 `Job` 的协程，在锁释放或加载结束后调用同一实例仍会抛出 `Nested invoke`；公开 KDoc 必须持续说明这一限制
- 上述独立协程被判为嵌套是已接受的限制，审查时不作为 bug 上报，也不要修复：正常项目不会这样启动协程，给嵌套标记加“已结束”状态的方案试过并已撤回
- `FLoader.load`、`tryLoad` 和 `cancelAndJoin` 都必须在进入任务簿记或锁等待前执行嵌套检查
- `mutate` 若先登记再做嵌套检查，被拦截的嵌套 `load` 会顶掉外层的 `_job`，之后的 `load` 取消不到外层，会一直等待

## 测试约定

- 库的 JVM 单元测试统一放在 `:app`，因为 `:lib` 没有配置测试依赖
- `:app` 单元测试通过 `friendPaths` 访问同一构建变体的 `:lib` 内部成员
- `MutatorTest` 显式抑制 `INVISIBLE_REFERENCE` 和 `INVISIBLE_MEMBER`，兼容 IDE 未识别跨模块友元关系的分析
- 测试使用 `kotlinx-coroutines-test` 的 `runTest`、`runCurrent`、`advanceUntilIdle`，Flow 断言使用 Turbine
- `runTest` 的默认超时在 `app/build.gradle.kts` 中设为 10 秒，卡住的用例能尽快失败；需要更久的用例给 `runTest` 显式传 `timeout`
- 测试方法名使用反引号包裹的英文句子，例如 ``fun `test load when loading`() = runTest { ... }``
- 跨协程边界验证异常原始实例时使用带业务字段的异常，避免调试模式的堆栈恢复复制异常
- 验证并发执行顺序时，通常向字符串或列表形式的 `container` 追加标记
- 修改并发逻辑时至少覆盖成功、普通异常、取消、忙状态、嵌套调用、锁释放和 `isBusyFlow` 状态序列
- 引用释放测试在 `runTest` 退出后等待真实 GC，并保持 loader 存活且不再发起加载
- 验证清理期间的引用释放时，用独立根 scope 保持旧任务清理挂起，GC 检查后再放行
- 排队加载结束后的引用释放必须在旧任务清理期间验证：替换它的 `load` 和 `cancelAndJoin` 此时还在等待，放行清理后再检查发现不了它们的持有
- 运行中的加载结束后的引用释放必须在替换它的新加载仍在运行时验证：新加载立即结束的话，检查时它已不存在，发现不了它对旧任务的持有
- 多线程压力用例的轮数按竞态窗口定：验证 `tryLoad` 判忙与登记的原子性用 1000 轮，机器满载时 100 轮发现不了回归
- 验证已取消的 `tryLoad` 调用方不让空闲 Loader 变忙用 2 万轮：回归时首次误判忙都出现在前 700 轮内，机器满载时也一样
- 验证 `mutate` 进入时登记的加锁用 3000 轮：去掉 `synchronized` 后首次失败都出现在前 700 轮内，机器满载时也一样
- 回调都能自行结束的压力用例发现不了任务登记出错：串行执行有 `_mutateMutex` 兜底，重叠断言不会失败
- 验证任务登记的压力用例让多数回调只在被取消时结束，并用收尾协程反复 `load` 替换，直到所有调用方结束：运行中的任务没人取消时调用方无法结束，靠超时失败
- 收尾协程的 `load` 自己也会被替换或取消，必须捕获取消异常后继续，否则收尾协程静默结束，用例卡住
- 压力用例里 `NonCancellable` 中的调用方超时取消不掉，它的回调必须能自行结束
- 调用方在 `NonCancellable` 中且回调不会自行结束的用例必须带 `@Test(timeout)`：回归时调用方不会结束，`runTest` 的超时也结束不了它，全量测试会一直卡住
- 压力用例里 `cancelAndJoin` 正常返回后，校验调用前已进入的回调都已退出
- 验证取消期间内联发起的加载不受影响时，让它保持挂起到取消方返回之后；同步完成的加载发现不了误取消和多余的等待
- 断言被取消的加载没有执行完回调时，回调里等到放行信号后要再检查 `ensureActive`：取消方不等待就返回时，信号可能先于 `await` 完成，此时 `await` 不挂起也不检查取消
- 验证失败或被拒绝的调用没有改动任务登记时，让旧任务保持运行，再用新的 `load` 替换它；`tryLoad` 判忙和调用方取消发现不了登记被破坏
- 需要让某个任务停在恢复前时，给它单独的 `TestCoroutineScheduler`
- `FMutator` 的加锁范围中，`mutateOrThrow` 的判忙与登记、`mutate` 进入时的登记有压力用例保护
- `cancelAndJoin` 的读取、`isBusy` 的读取和任务结束时的清空有持锁用例保护：借锁内创建的 `newBusyCause()` 从外部持有 `_lock`，再断言目标线程停在 `BLOCKED`
- 持锁用例依赖忙异常在 `_lock` 内创建；把 `newBusyCause()` 挪到锁外时要同步改写这些用例
- `mutate` 的顶替判断与登记 `_runningJob` 去掉 `synchronized` 或拆成两次加锁，压力用例不能稳定发现，改动时靠审查
- 拆成两次加锁在 3000 轮下约四成概率被压力用例发现，放大轮数后首次失败出现在 1300～8400 轮
- 拆成两次加锁没有便宜的确定性用例，试过后维持靠审查：持锁用例发现不了，拆开后每一半仍各自加锁；专门制造这个竞态的压力用例 20 万轮也有漏掉的；在库内加测试钩子只能固定钩子位置
- 判忙从任务未完成改成字段非 `null` 有持锁用例保护：持锁期间让任务结束，它的清空被锁挡住，此时在持锁线程上调用 `isBusy` 必须返回 `false`
- 上述用例依赖 `_lock` 可重入，以及 `mutateOrThrow` 的判忙复用 `isBusy`
- `syncBusyFlow` 在锁外赋值有用例保护：让 Unconfined 收集者卡在赋值调用里，再断言其他线程的 `isBusy` 没有停在 `BLOCKED`
- `syncBusyFlow` 去掉赋值后的复查，没有用例能发现，改动时靠审查：出错需要线程恰好在读取和赋值之间被抢占，多线程压力 3000 轮撞不上
- 真实 `Dispatchers.Main.immediate` 上的收集者重入不加常驻设备测试，审查时不作为覆盖缺口上报
- 不加是因为库代码不区分调度器：这类用例验证的是协程库的主线程分发行为，不是本库逻辑
- 设备测试要模拟器才能跑，不在主要验证项里，平时不会执行
- 这条路径在 API 35 模拟器、`kotlinx-coroutines` 1.8.1 下临时验证过：收集者内联取消、内联替换，以及调用方也在 `Main.immediate` 上时收集者延后运行，结果都与 Unconfined 用例一致
- 升级 `kotlinx-coroutines` 后需要确认时，在模拟器上临时跑一次即可

| 测试类 | 覆盖范围 |
|---|---|
| `LoaderTest` | 加载结果、取消（含先被替换再被 `cancelAndJoin` 取消时保留替换原因，先被替换或被 `cancelAndJoin` 取消再被调用方取消时保留最先原因，`onLoad` 捕获取消后抛出其他取消异常，以及已取消的调用方不妨碍之后的 `load` 替换运行中的加载）、忙状态（含忙异常不取消调用方，判忙后 `load` 仍能替换运行中的加载，以及 `isBusy` 在回调内、取消后清理期间和排队加载尚未恢复时为忙）、排队（含旧任务结束后恢复前被 `cancelAndJoin` 取消且此时 `tryLoad` 仍判忙，`cancelAndJoin` 在旧任务清理结束后仍等被取消的排队任务退出，新 `load` 不等被它替换的排队任务退出且此时已不算忙，排队任务刚登记就被调用方取消后旧任务仍被取消，以及旧任务调用方取消后新 `load` 与 `cancelAndJoin` 等待清理且保留调用方原因）、多线程（含 `onLoad` 抛普通异常、Unconfined 下混合 `tryLoad`，回调只在被取消时结束时调用方都能结束且 `cancelAndJoin` 返回前回调已退出，并校验取消原因类型）、嵌套（含新根 scope 绕开检测后 `load` 替换外层、`tryLoad` 判忙穿透外层、`cancelAndJoin` 取消外层，以及继承上下文的独立协程在加载结束后仍被判为嵌套）、Unconfined 下运行中和排队中调用方 `finally` 内联重入（含被替换时 `tryLoad` 判忙、被 `cancelAndJoin` 取消时运行中调用方的 `tryLoad` 立即执行而排队调用方的 `tryLoad` 判忙、被 `cancelAndJoin` 取消时 `finally` 内发起的加载保持运行且不被取消或等待，以及排队调用方被替换时 `cancelAndJoin` 取消新 `load`）、Unconfined 收集者在 `isBusyFlow` 变化时内联调用 `load`、`tryLoad` 和 `cancelAndJoin`（含变为 `true` 时取消或替换后 `onLoad` 不执行且分别由 `load` 和 `tryLoad` 触发，恢复 `false` 时不影响上一次加载的结果且 `tryLoad` 不判忙，以及调用方也在 Unconfined 上时收集者等调用方挂起后才运行）、`isBusyFlow`（含普通异常、重新加载、排队加载被替换和 `tryLoad` 被替换时没有多余的翻转，以及取值和只读） |
| `LoaderCallbackTest` | `load` 与 `tryLoad` 的异常包装、子协程生命周期（含回调抛取消异常时子协程收到同一原因并等待清理、子协程内其他 Loader 的忙异常不传播，以及独立 scope 启动的协程不延长加载）、线程上下文安装期间的取消、回调内的嵌套调用（含被拦截后新的 `load` 仍能替换外层加载）和 Flow 上下文约束 |
| `LoaderQueuedCleanupTest` | `load` 与 `tryLoad` 发起的任务在排队调用方取消或超时后仍保持忙状态和清理等待，以及之后的新 `load` 等待清理后执行 |
| `LoaderReferenceTest` | `load` 与 `tryLoad` 在成功、普通异常和取消异常退出后释放结果、异常数据及回调闭包捕获的对象，被替换或 `cancelAndJoin` 取消后释放回调闭包捕获的对象（含替换它的新加载仍在运行时），以及排队加载被替换、被 `cancelAndJoin` 取消或被调用方取消后释放回调闭包，被调用方取消后释放异常数据（含旧任务仍在清理时，排队加载被替换或被 `cancelAndJoin` 取消后释放回调闭包，排队调用方取消后释放回调闭包和取消数据） |
| `MutatorTest` | 已取消调用方的任务登记（含多线程下不让空闲 Loader 变忙）、已登记 `load` 与 `tryLoad` 的取消和替换、上下文探针的暂停位置，新任务登记后尚未发起取消时的排队任务替换、手动取消和忙状态，进入时没有运行任务的任务在新任务登记后尚未发起取消时也不执行，被顶替的排队任务不登记为运行任务，`cancelAndJoin` 的取消顺序，`isBusy` 在任务各阶段的取值（含排队任务尚未恢复时和排队任务结束后旧任务仍在清理时算忙，任务完成到清空登记之间不算忙），`isBusyFlow` 在任务各阶段的取值和序列（含登记后进入 block 前已为 `true`，排队和接替执行期间没有多余的翻转，判忙的调用不改变状态，以及多线程下旧任务结束与新任务登记并发后与登记一致），`isBusyFlow` 在锁外赋值，以及任务结束时的清空、`cancelAndJoin` 的读取和 `isBusy` 的读取在锁内执行 |
| `MutexTest` | 互斥（含多线程和 Unconfined）、锁释放（含 `action` 抛出取消异常，以及吞掉取消后正常返回时仍抛出）、嵌套（含子协程、隔着其他实例回到同一实例，新根 scope 绕开检测后等待锁，以及继承上下文的独立协程在锁释放后仍被判为嵌套）和 Flow 上下文约束 |

## 审查约定

- 「并发语义与不可破坏的约束」中已写明且有用例固定的行为按设计处理，审查时不作为 bug 上报，也不提修复方案
- 这类行为如果公开 KDoc 没有说明，可以建议补充说明

## 编码与发布约定

- Kotlin 代码使用 2 空格缩进，注释和 KDoc 使用中文
- 不要无意扩大公开 API；新增或修改公开 API 时提供简洁 KDoc
- 每次行为变化都更新 `CHANGELOG.md`，使用现有的 `⚠️ Breaking Changes`、`✨ Improvements`、`🐛 Bug Fixes`、`📝 Documentation` 分类；不对使用方产生影响的内部改动不写入 changelog
- 破坏性变更在 changelog 中附带 `Migration` 代码示例
- 发版时更新 `lib/gradle.properties` 中的 `VERSION_NAME`；版本发布提交标题使用版本号，例如 `1.7.1`
