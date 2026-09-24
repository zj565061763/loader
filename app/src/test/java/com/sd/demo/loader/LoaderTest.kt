package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FLoader
import com.sd.lib.loader.loadingFlow
import com.sd.lib.loader.safeRunCatching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class LoaderTest {

  @Test
  fun `test load when success`() = runTest {
    val loader = FLoader()
    loader.load {
      assertEquals(true, loader.isLoading())
      1
    }.also { result ->
      assertEquals(1, result.getOrThrow())
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when error in block`() = runTest {
    val loader = FLoader()
    loader.load {
      assertEquals(true, loader.isLoading())
      error("error in block")
    }.also { result ->
      assertEquals("error in block", result.exceptionOrNull()!!.message)
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when loading`() = runTest {
    val loader = FLoader()
    var container = ""

    val job = launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    loader.load {
      assertEquals(true, loader.isLoading())
      assertEquals("1", container)
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      2
    }.also { result ->
      assertEquals(2, result.getOrThrow())
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when cancel`() = runTest {
    val loader = FLoader()
    var container = ""
    launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also { job ->
      runCurrent()
      loader.cancelAndJoin()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals("1", container)
      assertEquals(false, loader.isLoading())
    }
  }

  @Test
  fun `test load when throw CancellationException in block`() = runTest {
    val loader = FLoader()
    launch {
      loader.load {
        assertEquals(true, loader.isLoading())
        throw CancellationException()
      }
    }.also { job ->
      runCurrent()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals(false, loader.isLoading())
    }
  }

  @Test
  fun `test load when cancel in block`() = runTest {
    val loader = FLoader()
    launch {
      loader.load {
        currentCoroutineContext().cancel()
      }
    }.also { job ->
      runCurrent()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals(false, loader.isLoading())
    }
  }

  @Test
  fun `test load when error after cancel`() = runTest {
    val loader = FLoader()
    val job = async {
      runCatching {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } catch (_: CancellationException) {
            error("error after cancel")
          }
        }
      }.exceptionOrNull()
    }.also {
      runCurrent()
    }

    loader.cancelAndJoin()
    // 取消后 onLoad 抛出的普通异常不能作为 Result 返回
    assertEquals(true, job.await() is CancellationException)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test cancelAndJoin in block`() = runTest {
    val loader = FLoader()
    loader.load {
      loader.cancelAndJoin()
    }.also { result ->
      // block 内嵌套调用 cancelAndJoin 会被拦截，避免自 join 死锁
      assertEquals("Nested invoke", result.exceptionOrNull()!!.message)
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test cancelAndJoin in block with NonCancellable`() = runTest {
    val loader = FLoader()
    // 即使用 NonCancellable 包裹，也不会死锁，而是抛出 Nested invoke
    loader.load {
      withContext(NonCancellable) {
        loader.cancelAndJoin()
      }
    }.also { result ->
      assertEquals("Nested invoke", result.exceptionOrNull()!!.message)
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test loadingFlow`() = runTest {
    val loader = FLoader()
    loader.loadingFlow.test {
      loader.load {}
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
    }
  }

  @Test
  fun `test loadingFlow when Reload`() = runTest {
    val loader = FLoader()
    loader.loadingFlow.test {
      launch {
        loader.load { delay(Long.MAX_VALUE) }
      }.also {
        runCurrent()
        loader.load { }
      }
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
    }
  }

  @Test
  fun `test loadingFlow when cancel`() = runTest {
    val loader = FLoader()
    loader.loadingFlow.test {
      launch {
        loader.load { delay(Long.MAX_VALUE) }
      }.also {
        runCurrent()
        loader.cancelAndJoin()
      }
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
    }
  }

  @Test
  fun `test tryLoad`() = runTest {
    val loader = FLoader()

    val job = launch {
      loader.load { delay(Long.MAX_VALUE) }
    }.also {
      runCurrent()
    }

    runCatching { loader.tryLoad { 1 } }.also { result ->
      assertEquals(true, result.exceptionOrNull() is FLoader.BusyCancellationException)
      assertEquals("Loader is busy", result.exceptionOrNull()?.message)
    }
    assertEquals(true, loader.isLoading())

    job.cancelAndJoin()

    loader.tryLoad { 2 }.also { result ->
      assertEquals(2, result.getOrThrow())
    }
  }

  @Test
  fun `test nested load`() = runTest {
    val loader = FLoader()
    val list = mutableListOf<String>()

    loader.load {
      runCatching {
        loader.load { }
      }.also {
        assertEquals("Nested invoke", it.exceptionOrNull()!!.message)
        list.add("1")
      }
      list.add("2")
    }.getOrThrow()

    assertEquals(listOf("1", "2"), list)
  }

  @Test
  fun `test nested tryLoad`() = runTest {
    val loader = FLoader()

    loader.load {
      runCatching {
        loader.tryLoad { }
      }.also {
        assertEquals("Nested invoke", it.exceptionOrNull()!!.message)
      }
    }.getOrThrow()
  }

  @Test
  fun `test nested load with other loader`() = runTest {
    val loader = FLoader()
    val otherLoader = FLoader()

    loader.load {
      otherLoader.load {
        runCatching {
          loader.load { }
        }.also {
          assertEquals("Nested invoke", it.exceptionOrNull()!!.message)
        }
      }.getOrThrow()
    }.getOrThrow()
  }

  @Test
  fun `test nested load in child coroutine`() = runTest {
    val loader = FLoader()
    // 子协程继承上下文，嵌套调用同样会被检测
    loader.load {
      coroutineScope {
        launch { loader.load { } }
      }
    }.also { result ->
      assertEquals("Nested invoke", result.exceptionOrNull()!!.message)
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load other loader in block`() = runTest {
    val loader = FLoader()
    val otherLoader = FLoader()

    loader.load {
      otherLoader.load { 1 }
    }.also { result ->
      assertEquals(1, result.getOrThrow().getOrThrow())
    }
  }

  @Test
  fun `test tryLoad busy from other loader propagates`() = runTest {
    val loader = FLoader()
    val otherLoader = FLoader()

    val otherJob = launch {
      otherLoader.load { delay(Long.MAX_VALUE) }
    }.also {
      runCurrent()
    }

    runCatching {
      loader.load {
        otherLoader.tryLoad { }
      }
    }.also { result ->
      assertEquals(true, result.exceptionOrNull() is FLoader.BusyCancellationException)
    }

    assertEquals(false, loader.isLoading())
    assertEquals(true, otherLoader.isLoading())
    assertEquals(false, otherJob.isCancelled)

    otherJob.cancelAndJoin()
  }

  @Test
  fun `test tryLoad when error in block`() = runTest {
    val loader = FLoader()
    loader.tryLoad {
      assertEquals(true, loader.isLoading())
      error("error in block")
    }.also { result ->
      assertEquals("error in block", result.exceptionOrNull()!!.message)
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test tryLoad when busy not cancel loading`() = runTest {
    val loader = FLoader()
    var container = ""

    val job = launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    runCatching { loader.tryLoad { } }
    assertEquals(true, loader.isLoading())
    assertEquals("", container)
    assertEquals(false, job.isCancelled)

    job.cancelAndJoin()
    assertEquals("1", container)
  }

  @Test
  fun `test load when tryLoad loading`() = runTest {
    val loader = FLoader()
    var container = ""

    val job = launch {
      loader.tryLoad {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    // tryLoad 发起的加载同样会被新的 load 取消
    loader.load {
      assertEquals("1", container)
      assertEquals(true, job.isCancelled)
      2
    }.also { result ->
      assertEquals(2, result.getOrThrow())
    }
  }

  @Test
  fun `test cancelAndJoin when tryLoad loading`() = runTest {
    val loader = FLoader()
    var container = ""

    val job = launch {
      loader.tryLoad {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    loader.cancelAndJoin()
    assertEquals(true, job.isCancelled)
    assertEquals("1", container)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test tryLoad when cancelAndJoin waiting cleanup`() = runTest {
    val loader = FLoader()
    var container = ""

    launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          withContext(NonCancellable) { delay(1000) }
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    val cancelJob = launch { loader.cancelAndJoin() }.also { runCurrent() }

    // 旧任务取消中，tryLoad 立即判定为忙，不等待清理
    val startTime = currentTime
    runCatching { loader.tryLoad { container += "2" } }.also { result ->
      assertEquals(true, result.exceptionOrNull() is FLoader.BusyCancellationException)
    }
    assertEquals(startTime, currentTime)
    assertEquals("", container)

    cancelJob.join()
    assertEquals("1", container)

    loader.tryLoad { container += "2" }.getOrThrow()
    assertEquals("12", container)
  }

  @Test
  fun `test tryLoad when load waiting previous cleanup`() = runTest {
    val loader = FLoader()
    var container = ""

    launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          withContext(NonCancellable) { delay(1000) }
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    val loadJob = launch { loader.load { container += "2" } }.also { runCurrent() }

    // 新任务正在等待旧任务清理，tryLoad 立即判定为忙
    val startTime = currentTime
    runCatching { loader.tryLoad { container += "3" } }.also { result ->
      assertEquals(true, result.exceptionOrNull() is FLoader.BusyCancellationException)
    }
    assertEquals(startTime, currentTime)

    loadJob.join()
    assertEquals("12", container)
  }

  @Test
  fun `test load when caller already cancelled`() = runTest {
    val loader = FLoader()
    var container = ""

    val loadingJob = launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    launch {
      currentCoroutineContext().cancel()
      loader.load { container += "2" }
    }.also { cancelledJob ->
      runCurrent()
      assertEquals(true, cancelledJob.isCancelled)
      assertEquals(true, cancelledJob.isCompleted)
    }

    assertEquals(true, loader.isLoading())
    assertEquals(false, loadingJob.isCancelled)
    assertEquals("", container)

    loadingJob.cancelAndJoin()
    assertEquals("1", container)
  }

  @Test
  fun `test tryLoad when caller already cancelled`() = runTest {
    val loader = FLoader()
    var container = ""

    val loadingJob = launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    launch {
      currentCoroutineContext().cancel()
      loader.tryLoad { container += "2" }
    }.also { cancelledJob ->
      runCurrent()
      assertEquals(true, cancelledJob.isCancelled)
      assertEquals(true, cancelledJob.isCompleted)
    }

    assertEquals(true, loader.isLoading())
    assertEquals(false, loadingJob.isCancelled)
    assertEquals("", container)

    loadingJob.cancelAndJoin()
    assertEquals("1", container)

    // 空闲时已取消的调用方也不会执行 onLoad
    launch {
      currentCoroutineContext().cancel()
      loader.tryLoad { container += "2" }
    }.also { cancelledJob ->
      runCurrent()
      assertEquals(true, cancelledJob.isCancelled)
    }
    assertEquals("1", container)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test concurrent load and tryLoad on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(100) {
        val loader = FLoader()
        val start = CompletableDeferred<Unit>()
        val running = AtomicInteger()
        val started = AtomicInteger()
        val overlapped = AtomicBoolean()
        val onLoad: suspend () -> Unit = {
          if (running.incrementAndGet() != 1) overlapped.set(true)
          started.incrementAndGet()
          try {
            yield()
          } finally {
            running.decrementAndGet()
          }
        }

        coroutineScope {
          val loadJobs = List(8) {
            async {
              start.await()
              try {
                loader.load(onLoad).getOrThrow()
              } catch (_: CancellationException) {
                // 被后续任务取消属于预期行为
              }
            }
          }
          val tryLoadJobs = List(8) {
            async {
              start.await()
              try {
                loader.tryLoad(onLoad).getOrThrow()
              } catch (_: CancellationException) {
                // 忙状态或被后续任务取消属于预期行为
              }
            }
          }
          start.complete(Unit)
          (loadJobs + tryLoadJobs).awaitAll()
        }

        // 所有调用结束后应处于空闲，tryLoad 不能误判为忙
        loader.tryLoad { }.getOrThrow()
        assertEquals(false, overlapped.get())
        assertEquals(0, running.get())
        assertEquals(true, started.get() > 0)
        assertEquals(false, loader.isLoading())
      }
    }
  }

  @Test
  fun `test concurrent cancelAndJoin on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(50) {
        val loader = FLoader()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cleaned = AtomicBoolean()
        val loadJob = async {
          try {
            loader.load {
              started.complete(Unit)
              try {
                delay(Long.MAX_VALUE)
              } finally {
                withContext(NonCancellable) {
                  release.await()
                  cleaned.set(true)
                }
              }
            }.getOrThrow()
          } catch (_: CancellationException) {
            // cancelAndJoin 取消加载属于预期行为
          }
        }

        started.await()
        coroutineScope {
          val cancelStarted = AtomicInteger()
          val cancelJobs = List(8) {
            async {
              cancelStarted.incrementAndGet()
              loader.cancelAndJoin()
              // cancelAndJoin 必须等待清理结束才返回
              assertEquals(true, cleaned.get())
            }
          }
          while (cancelStarted.get() < cancelJobs.size) yield()
          release.complete(Unit)
          cancelJobs.awaitAll()
        }

        loadJob.await()
        assertEquals(false, loader.isLoading())
      }
    }
  }

  @Test
  fun `test concurrent cancelAndJoin and tryLoad when idle`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(100) {
        val loader = FLoader()
        val start = CompletableDeferred<Unit>()

        coroutineScope {
          val cancelJobs = List(8) {
            async {
              start.await()
              loader.cancelAndJoin()
            }
          }
          val tryLoadJob = async {
            start.await()
            runCatching { loader.tryLoad { yield() }.getOrThrow() }.exceptionOrNull()
          }

          start.complete(Unit)
          cancelJobs.awaitAll()

          // 空闲取消不能占用任务锁并导致 tryLoad 误报忙
          assertEquals(false, tryLoadJob.await() is FLoader.BusyCancellationException)
        }
      }
    }
  }

  @Test
  fun `test cancelAndJoin when caller already cancelled and load waiting previous cleanup`() = runTest {
    val loader = FLoader()
    var container = ""

    launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          withContext(NonCancellable) { delay(1000) }
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    // 新的 load 等待旧任务清理
    val loadJob = async {
      runCatching { loader.load { container += "2" } }.exceptionOrNull()
    }.also {
      runCurrent()
    }

    launch {
      currentCoroutineContext().cancel()
      loader.cancelAndJoin()
    }.also { cancelledJob ->
      runCurrent()
      // 不等待旧任务清理
      assertEquals(true, cancelledJob.isCompleted)
    }

    // 等待旧任务清理的 load 也会被取消，并立即返回
    assertEquals(true, loadJob.await() is CancellationException)
    assertEquals("", container)

    advanceUntilIdle()
    assertEquals("1", container)
    assertEquals(false, loader.isLoading())

    // 之后发起的加载不受影响
    loader.load { container += "3" }.getOrThrow()
    assertEquals("13", container)
  }

  @Test
  fun `test cancelAndJoin when caller already cancelled and multiple loads waiting`() = runTest {
    val loader = FLoader()
    var container = ""

    launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          withContext(NonCancellable) { delay(1000) }
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    // 第一个 load 持有任务锁等待旧任务清理，第二个 load 等待任务锁
    val loadJobs = List(2) { index ->
      async {
        runCatching { loader.load { container += "${index + 2}" } }.exceptionOrNull()
      }
    }.also {
      runCurrent()
    }

    launch {
      currentCoroutineContext().cancel()
      loader.cancelAndJoin()
    }.also {
      runCurrent()
    }

    // 调用方已取消时也要先取消全部任务，不能因等待失败而漏掉后面的任务
    loadJobs.forEach { assertEquals(true, it.await() is CancellationException) }
    advanceUntilIdle()
    assertEquals("1", container)
    assertEquals(false, loader.isLoading())
  }

  // 回归时会在单线程上忙等，用超时让测试失败而不是卡住
  @Test(timeout = 10_000)
  fun `test load while cancelAndJoin waiting cleanup`() = runTest {
    val loader = FLoader()
    var container = ""

    launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          withContext(NonCancellable) { delay(1000) }
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    val cancelJob = launch {
      loader.cancelAndJoin()
      container += "2"
    }.also {
      runCurrent()
    }

    // cancelAndJoin 等待旧任务清理期间发起的 load 不受影响
    loader.load { container += "3" }.getOrThrow()
    cancelJob.join()
    assertEquals("123", container)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test concurrent cancelAndJoin and load waiting previous cleanup on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(100) { index ->
        // 交替验证调用方正常和已取消两种情况
        val callerCancelled = index % 2 == 0
        val loader = FLoader()
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val cleaned = AtomicBoolean()
        val loaded = AtomicBoolean()

        val firstJob = launch {
          runCatching {
            loader.load {
              started.complete(Unit)
              try {
                delay(Long.MAX_VALUE)
              } finally {
                withContext(NonCancellable) {
                  cleanupStarted.complete(Unit)
                  releaseCleanup.await()
                  cleaned.set(true)
                }
              }
            }
          }
        }
        started.await()

        val loadJob = async {
          runCatching {
            loader.load {
              releaseLoad.await()
              loaded.set(true)
            }
          }.exceptionOrNull()
        }
        // load 开始等待旧任务清理后，cancelAndJoin 与旧任务清理结束并发
        cleanupStarted.await()
        val cancelJob = launch {
          if (callerCancelled) currentCoroutineContext().cancel()
          loader.cancelAndJoin()
          // 调用方正常时，返回前旧任务必须已清理结束
          assertEquals(true, cleaned.get())
        }
        releaseCleanup.complete(Unit)

        cancelJob.join()
        releaseLoad.complete(Unit)
        // 无论调用方是否已取消，等待旧任务清理的 load 都会被取消
        assertEquals(true, loadJob.await() is CancellationException)
        assertEquals(false, loaded.get())
        firstJob.join()
        assertEquals(false, loader.isLoading())
      }
    }
  }

  @Test
  fun `test cancel when idle`() = runTest {
    val loader = FLoader()
    loader.cancelAndJoin()
    assertEquals(false, loader.isLoading())
    loader.load { 1 }.also { result ->
      assertEquals(1, result.getOrThrow())
    }
  }

  @Test
  fun `test stateFlow`() = runTest {
    val loader = FLoader()
    assertEquals(FLoader.State(isLoading = false), loader.stateFlow.value)
    loader.load {
      assertEquals(FLoader.State(isLoading = true), loader.stateFlow.value)
    }.getOrThrow()
    assertEquals(FLoader.State(isLoading = false), loader.stateFlow.value)
  }

  @Test
  fun `test safeRunCatching`() = runTest {
    safeRunCatching { 1 }.also { result ->
      assertEquals(1, result.getOrThrow())
    }

    safeRunCatching { error("error in block") }.also { result ->
      assertEquals("error in block", result.exceptionOrNull()!!.message)
    }

    runCatching {
      safeRunCatching { throw CancellationException() }
    }.also { result ->
      assertEquals(true, result.exceptionOrNull() is CancellationException)
    }
  }
}
