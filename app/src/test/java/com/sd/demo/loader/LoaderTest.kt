package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FLoader
import com.sd.lib.loader.loadingFlow
import com.sd.lib.loader.safeRunCatching
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class LoaderTest {

  // 带业务字段的异常，用于验证类型和数据原样保留
  private class BusinessException(val code: Int) : RuntimeException("business error: $code")

  // 自定义取消原因，用于验证原始异常实例
  private class CustomCancellationException(val code: Int = 1) : CancellationException("custom cause")

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
    val cause = BusinessException(1)
    loader.load {
      assertEquals(true, loader.isLoading())
      throw cause
    }.also { result ->
      assertSame(cause, result.exceptionOrNull())
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when error in child coroutine`() = runTest {
    val loader = FLoader()
    val cause = BusinessException(2)
    val job = async {
      loader.load {
        CoroutineScope(currentCoroutineContext()).launch {
          delay(100)
          throw cause
        }
        1
      }
    }.also {
      runCurrent()
    }

    // onLoad 已返回，但子协程未结束，仍处于加载中
    assertEquals(true, loader.isLoading())
    assertEquals(false, job.isCompleted)

    // 子协程的普通异常包装为 Result.failure，不会直接抛出
    assertSame(cause, job.await().exceptionOrNull())
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when result is null`() = runTest {
    val loader = FLoader()
    assertNull(loader.load<String?> { null }.getOrThrow())
    assertNull(loader.tryLoad<String?> { null }.getOrThrow())
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when withTimeoutOrNull in block`() = runTest {
    val loader = FLoader()
    val job = async {
      loader.load {
        withTimeoutOrNull(100) { delay(Long.MAX_VALUE) }
      }
    }.also {
      runCurrent()
    }

    advanceUntilIdle()
    // 超时返回 null 并包装为 Result.success，不像 withTimeout 那样抛出异常
    assertNull(job.await().getOrThrow())
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
  fun `test load when cancelled by new load`() = runTest {
    val loader = FLoader()
    var exceptionInBlock: Throwable? = null

    val job = async {
      runCatching {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } catch (e: CancellationException) {
            exceptionInBlock = e
            throw e
          }
        }
      }.exceptionOrNull()
    }.also {
      runCurrent()
    }

    loader.load { }.getOrThrow()
    // onLoad 和调用方都收到 ReplacedCancellationException
    assertEquals(true, exceptionInBlock is FLoader.ReplacedCancellationException)
    assertEquals("Cancelled by new load", exceptionInBlock?.message)
    job.await().also { cause ->
      assertEquals(true, cause is FLoader.ReplacedCancellationException)
      assertEquals("Cancelled by new load", cause?.message)
    }
  }

  @Test
  fun `test load when cancelled by cancelAndJoin`() = runTest {
    val loader = FLoader()
    var exceptionInBlock: Throwable? = null

    val job = async {
      runCatching {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } catch (e: CancellationException) {
            exceptionInBlock = e
            throw e
          }
        }
      }.exceptionOrNull()
    }.also {
      runCurrent()
    }

    loader.cancelAndJoin()
    // onLoad 和调用方都收到 ManualCancellationException
    assertEquals(true, exceptionInBlock is FLoader.ManualCancellationException)
    assertEquals("Cancelled by cancelAndJoin", exceptionInBlock?.message)
    job.await().also { cause ->
      assertEquals(true, cause is FLoader.ManualCancellationException)
      assertEquals("Cancelled by cancelAndJoin", cause?.message)
    }
  }

  @Test
  fun `test load when cancelled by cancelAndJoin after replaced`() = runTest {
    val loader = FLoader()
    val releaseCleanup = CompletableDeferred<Unit>()
    var exceptionInBlock: Throwable? = null
    val firstJob = async {
      runCatching {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } catch (e: CancellationException) {
            exceptionInBlock = e
            throw e
          } finally {
            withContext(NonCancellable) { releaseCleanup.await() }
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      val queuedJob = async {
        runCatching { loader.load { 2 } }.exceptionOrNull()
      }.also { runCurrent() }
      assertTrue(exceptionInBlock is FLoader.ReplacedCancellationException)

      val cancelJob = launch { loader.cancelAndJoin() }.also { runCurrent() }
      assertTrue(queuedJob.await() is FLoader.ManualCancellationException)
      assertEquals(false, firstJob.isCompleted)

      releaseCleanup.complete(Unit)
      cancelJob.join()
      // 先被替换再被 cancelAndJoin 取消的加载保留最先的取消原因
      assertTrue(exceptionInBlock is FLoader.ReplacedCancellationException)
      assertTrue(firstJob.await() is FLoader.ReplacedCancellationException)
      assertEquals(false, loader.isLoading())
      assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
    } finally {
      releaseCleanup.complete(Unit)
    }
  }

  @Test
  fun `test load when throw other CancellationException after replaced`() = runTest {
    checkOtherCancellationAfterCancelled(replace = true)
  }

  @Test
  fun `test load when throw other CancellationException after cancelAndJoin`() = runTest {
    checkOtherCancellationAfterCancelled(replace = false)
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
    var loadingInBlock = false
    launch {
      loader.load {
        loadingInBlock = loader.isLoading()
        throw CancellationException()
      }
    }.also { job ->
      runCurrent()
      assertEquals(true, loadingInBlock)
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals(false, loader.isLoading())
    }
  }

  @Test
  fun `test load when throw custom CancellationException in block`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException()
    val exception = runCatching { loader.load { throw cause } }.exceptionOrNull()

    assertSame(cause, exception)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test tryLoad when throw custom CancellationException in block`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException()
    val exception = runCatching { loader.tryLoad { throw cause } }.exceptionOrNull()

    assertSame(cause, exception)
    assertEquals(false, loader.isLoading())
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
    assertEquals(true, job.await() is FLoader.ManualCancellationException)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when error after replaced`() = runTest {
    val loader = FLoader()
    val job = async {
      runCatching {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } catch (_: CancellationException) {
            error("error after replaced")
          }
        }
      }.exceptionOrNull()
    }.also {
      runCurrent()
    }

    loader.load { 2 }.also { assertEquals(2, it.getOrThrow()) }
    // 被替换后 onLoad 抛出的普通异常不能作为 Result 返回
    assertEquals(true, job.await() is FLoader.ReplacedCancellationException)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when caller cancelled with custom cause`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException()
    var thrown: Throwable? = null

    launch {
      try {
        loader.load { delay(Long.MAX_VALUE) }
      } catch (e: CancellationException) {
        thrown = e
      }
    }.also { job ->
      runCurrent()
      job.cancel(cause)
      runCurrent()
    }

    // 调用方的取消原因原样传播，不被替换为其他取消异常
    assertEquals(true, thrown === cause)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test tryLoad when caller cancelled with custom cause`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException()
    var thrown: Throwable? = null

    launch {
      try {
        loader.tryLoad { delay(Long.MAX_VALUE) }
      } catch (e: CancellationException) {
        thrown = e
      }
    }.also { job ->
      runCurrent()
      job.cancel(cause)
      runCurrent()
    }

    assertEquals(true, thrown === cause)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when withTimeout in block`() = runTest {
    val loader = FLoader()
    val job = async {
      runCatching {
        loader.load {
          withTimeout(100) { delay(Long.MAX_VALUE) }
        }
      }.exceptionOrNull()
    }.also {
      runCurrent()
    }

    advanceUntilIdle()
    // TimeoutCancellationException 原样抛出，不包装为 Result.failure
    assertEquals(true, job.await() is TimeoutCancellationException)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test tryLoad when withTimeout in block`() = runTest {
    val loader = FLoader()
    val job = async {
      runCatching {
        loader.tryLoad {
          withTimeout(100) { delay(Long.MAX_VALUE) }
        }
      }.exceptionOrNull()
    }.also {
      runCurrent()
    }

    advanceUntilIdle()
    assertEquals(true, job.await() is TimeoutCancellationException)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test cancelAndJoin in block`() = runTest {
    val loader = FLoader()
    loader.load {
      loader.cancelAndJoin()
    }.also { result ->
      // block 内嵌套调用 cancelAndJoin 会被拦截，避免自 join 死锁
      assertEquals(true, result.exceptionOrNull() is IllegalStateException)
      assertEquals("Nested invoke", result.exceptionOrNull()!!.message)
    }
    assertEquals(false, loader.isLoading())
  }

  @Test(timeout = 10_000)
  fun `test cancelAndJoin in block with NonCancellable`() = runTest {
    val loader = FLoader()
    // 即使用 NonCancellable 包裹，也不会死锁，而是抛出 Nested invoke
    loader.load {
      withContext(NonCancellable) {
        loader.cancelAndJoin()
      }
    }.also { result ->
      assertEquals(true, result.exceptionOrNull() is IllegalStateException)
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
  fun `test loadingFlow when error in block`() = runTest {
    val loader = FLoader()
    val cause = BusinessException(5)
    loader.loadingFlow.test {
      assertSame(cause, loader.load { throw cause }.exceptionOrNull())
      // 普通异常包装为 Result.failure 后状态序列与成功时一致
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
  fun `test loadingFlow when tryLoad replaced by load`() = runTest {
    val loader = FLoader()
    loader.loadingFlow.test {
      launch {
        loader.tryLoad { delay(Long.MAX_VALUE) }
      }.also {
        runCurrent()
        loader.load { }
      }
      // tryLoad 发起的加载被替换时，状态流与 load 被替换时一致
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
    }
  }

  @Test
  fun `test loadingFlow when queued load replaced`() = runTest {
    val loader = FLoader()
    val container = mutableListOf<String>()
    loader.loadingFlow.test {
      launch {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } finally {
            withContext(NonCancellable) { delay(1000) }
          }
        }
      }.also { runCurrent() }
      val queued = async {
        runCatching { loader.load { container.add("queued") } }.exceptionOrNull()
      }.also { runCurrent() }
      loader.load { container.add("latest") }.getOrThrow()
      assertTrue(queued.await() is FLoader.ReplacedCancellationException)

      // 被替换的排队加载不执行，状态流中没有它的翻转
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
      assertEquals(listOf("latest"), container)
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
        assertEquals(true, it.exceptionOrNull() is IllegalStateException)
        assertEquals("Nested invoke", it.exceptionOrNull()!!.message)
        list.add("1")
      }
      list.add("2")
    }.getOrThrow()

    assertEquals(listOf("1", "2"), list)
  }

  @Test
  fun `test nested load across dispatchers`() = runTest {
    val loader = FLoader()
    var nestedEntered = false

    val result = loader.load {
      withContext(Dispatchers.Default) {
        loader.load { nestedEntered = true }.getOrThrow()
      }
    }

    assertTrue(result.exceptionOrNull() is IllegalStateException)
    assertEquals("Nested invoke", result.exceptionOrNull()?.message)
    assertEquals(false, nestedEntered)
    assertEquals(false, loader.isLoading())
    assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
  }

  @Test
  fun `test nested tryLoad`() = runTest {
    val loader = FLoader()

    loader.load {
      runCatching {
        loader.tryLoad { }
      }.also {
        assertEquals(true, it.exceptionOrNull() is IllegalStateException)
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
          assertEquals(true, it.exceptionOrNull() is IllegalStateException)
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
      assertEquals(true, result.exceptionOrNull() is IllegalStateException)
      assertEquals("Nested invoke", result.exceptionOrNull()!!.message)
    }
    assertEquals(false, loader.isLoading())
  }

  @Test(timeout = 10_000)
  fun `test nested load in new root scope replaces outer load`() = runTest {
    val loader = FLoader()
    val container = mutableListOf<String>()
    // 独立的根 scope 不继承上下文，嵌套检测失效，内层 load 会替换外层而不是死锁
    val rootScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    try {
      val exception = runCatching {
        loader.load {
          container.add("outer-started")
          rootScope.async { loader.load { container.add("inner-load") }.getOrThrow() }.await()
          container.add("outer-returned")
        }
      }.exceptionOrNull()
      advanceUntilIdle()

      assertTrue(exception is FLoader.ReplacedCancellationException)
      assertEquals(listOf("outer-started", "inner-load"), container)
      assertEquals(false, loader.isLoading())
      assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
    } finally {
      rootScope.cancel()
    }
  }

  @Test(timeout = 10_000)
  fun `test nested tryLoad in new root scope throws busy from outer load`() = runTest {
    val loader = FLoader()
    val container = mutableListOf<String>()
    // 独立的根 scope 不继承上下文，嵌套检测失效，内层 tryLoad 立即判忙而不是死锁
    val rootScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    try {
      val exception = runCatching {
        loader.load {
          container.add("outer-started")
          rootScope.async { loader.tryLoad { container.add("inner-load") }.getOrThrow() }.await()
          container.add("outer-returned")
        }
      }.exceptionOrNull()

      // 忙异常是取消异常，从外层 onLoad 穿透后由外层 load 原样抛出
      assertTrue(exception is FLoader.BusyCancellationException)
      assertEquals(listOf("outer-started"), container)
      assertEquals(false, loader.isLoading())
      assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
    } finally {
      rootScope.cancel()
    }
  }

  @Test(timeout = 10_000)
  fun `test nested cancelAndJoin in new root scope cancels outer load`() = runTest {
    val loader = FLoader()
    val container = mutableListOf<String>()
    // 独立的根 scope 不继承上下文，嵌套检测失效，内层 cancelAndJoin 会取消外层而不是死锁
    val rootScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    try {
      val exception = runCatching {
        loader.load {
          container.add("outer-started")
          rootScope.async {
            loader.cancelAndJoin()
            container.add("inner-cancel-finished")
          }.await()
          container.add("outer-returned")
        }
      }.exceptionOrNull()
      advanceUntilIdle()

      // 外层等待内层时被取消，内层 cancelAndJoin 等外层结束后才返回
      assertTrue(exception is FLoader.ManualCancellationException)
      assertEquals(listOf("outer-started", "inner-cancel-finished"), container)
      assertEquals(false, loader.isLoading())
      assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
    } finally {
      rootScope.cancel()
    }
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
  fun `test cancelAndJoin other loader in block waits for cleanup`() = runTest {
    val loader = FLoader()
    val otherLoader = FLoader()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    val otherJob = async {
      runCatching {
        otherLoader.load {
          try {
            delay(Long.MAX_VALUE)
          } finally {
            withContext(NonCancellable) {
              cleanupStarted.complete(Unit)
              releaseCleanup.await()
            }
            container.add("other-cleaned")
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      val loading = async {
        loader.load {
          container.add("outer-started")
          otherLoader.cancelAndJoin()
          container.add("outer-resumed")
          2
        }
      }.also { runCurrent() }

      assertEquals(true, cleanupStarted.isCompleted)
      assertEquals(false, otherJob.isCompleted)
      assertEquals(false, loading.isCompleted)
      assertEquals(true, otherLoader.isLoading())
      assertEquals(true, loader.isLoading())
      assertEquals(listOf("outer-started"), container)

      releaseCleanup.complete(Unit)
      assertEquals(2, loading.await().getOrThrow())
      assertTrue(otherJob.await() is FLoader.ManualCancellationException)
      assertEquals(listOf("outer-started", "other-cleaned", "outer-resumed"), container)
      assertEquals(false, otherLoader.isLoading())
      assertEquals(false, loader.isLoading())
      assertEquals(3, otherLoader.tryLoad { 3 }.getOrThrow())
      assertEquals(4, loader.tryLoad { 4 }.getOrThrow())
    } finally {
      releaseCleanup.complete(Unit)
      otherJob.cancelAndJoin()
    }
  }

  @Test
  fun `test load other loader in block when cancelled by cancelAndJoin`() = runTest {
    val loader = FLoader()
    val otherLoader = FLoader()
    var otherException: Throwable? = null

    launch {
      loader.load {
        try {
          otherLoader.load { delay(Long.MAX_VALUE) }
        } catch (e: CancellationException) {
          otherException = e
          throw e
        }
      }
    }.also {
      runCurrent()
    }

    loader.cancelAndJoin()
    // 取消原因会传给内层，内层抛出的是外层的 ManualCancellationException
    assertEquals(true, otherException is FLoader.ManualCancellationException)
    assertEquals(false, otherLoader.isLoading())
  }

  @Test
  fun `test load other loader in block when cancelled by new load`() = runTest {
    val loader = FLoader()
    val otherLoader = FLoader()
    val container = mutableListOf<String>()
    var exceptionInBlock: Throwable? = null
    var otherException: Throwable? = null

    val firstJob = async {
      runCatching {
        loader.load {
          try {
            otherLoader.load {
              try {
                delay(Long.MAX_VALUE)
              } catch (e: CancellationException) {
                exceptionInBlock = e
                throw e
              } finally {
                withContext(NonCancellable) { delay(100) }
                container.add("inner-cleaned")
              }
            }
          } catch (e: CancellationException) {
            otherException = e
            throw e
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }
    assertEquals(true, loader.isLoading())
    assertEquals(true, otherLoader.isLoading())

    val nextJob = async {
      loader.load {
        assertEquals(listOf("inner-cleaned"), container)
        assertEquals(false, otherLoader.isLoading())
        container.add("new-load")
        2
      }
    }.also { runCurrent() }

    // 内层收到替换取消后仍在清理，新加载必须等待
    assertEquals(true, exceptionInBlock is FLoader.ReplacedCancellationException)
    assertEquals(false, firstJob.isCompleted)
    assertEquals(false, nextJob.isCompleted)
    assertEquals(emptyList<String>(), container)

    advanceUntilIdle()
    assertEquals(true, otherException is FLoader.ReplacedCancellationException)
    assertEquals(true, firstJob.await() is FLoader.ReplacedCancellationException)
    assertEquals(2, nextJob.await().getOrThrow())
    assertEquals(listOf("inner-cleaned", "new-load"), container)
    assertEquals(false, loader.isLoading())
    assertEquals(false, otherLoader.isLoading())
    assertEquals(3, otherLoader.tryLoad { 3 }.getOrThrow())
  }

  @Test
  fun `test load other loader in block when other loader cancelled by cancelAndJoin`() = runTest {
    checkOtherLoaderCancelledInBlock(replace = false)
  }

  @Test
  fun `test load other loader in block when other loader cancelled by new load`() = runTest {
    checkOtherLoaderCancelledInBlock(replace = true)
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
    val cause = BusinessException(3)
    loader.tryLoad {
      assertEquals(true, loader.isLoading())
      throw cause
    }.also { result ->
      assertSame(cause, result.exceptionOrNull())
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

    runCatching { loader.tryLoad { } }.also { result ->
      assertEquals(true, result.exceptionOrNull() is FLoader.BusyCancellationException)
    }
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

    val job = async {
      runCatching {
        loader.tryLoad {
          try {
            delay(Long.MAX_VALUE)
          } finally {
            container += "1"
          }
        }
      }.exceptionOrNull()
    }.also {
      runCurrent()
    }

    // tryLoad 发起的加载同样会被新的 load 取消
    loader.load {
      assertEquals("1", container)
      2
    }.also { result ->
      assertEquals(2, result.getOrThrow())
    }
    assertEquals(true, job.await() is FLoader.ReplacedCancellationException)
  }

  @Test
  fun `test cancelAndJoin when tryLoad loading`() = runTest {
    val loader = FLoader()
    var container = ""

    val job = async {
      runCatching {
        loader.tryLoad {
          try {
            delay(Long.MAX_VALUE)
          } finally {
            container += "1"
          }
        }
      }.exceptionOrNull()
    }.also {
      runCurrent()
    }

    loader.cancelAndJoin()
    assertEquals("1", container)
    assertEquals(true, job.await() is FLoader.ManualCancellationException)
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
  fun `test load when previous load waiting cleanup`() = runTest {
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

    val loadJob = async {
      runCatching { loader.load { container += "2" } }.exceptionOrNull()
    }.also {
      runCurrent()
    }

    val startTime = currentTime
    launch { loader.load { container += "3" } }.also { runCurrent() }

    // 等待旧任务清理的 load 被新的 load 立即取消，不等清理结束
    assertEquals(true, loadJob.isCompleted)
    assertEquals(true, loadJob.await() is FLoader.ReplacedCancellationException)

    advanceUntilIdle()
    // 被取消的 load 不会执行，最新的 load 只等旧任务清理一次
    assertEquals("13", container)
    assertEquals(startTime + 1000, currentTime)
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load waits for previous load cleanup when its caller cancelled`() = runTest {
    checkLoadAfterCallerCancelledCleanup(useTryLoad = false)
  }

  @Test
  fun `test load waits for previous tryLoad cleanup when its caller cancelled`() = runTest {
    checkLoadAfterCallerCancelledCleanup(useTryLoad = true)
  }

  @Test
  fun `test cancelling newest queued caller does not revive replaced load`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<Int>()
    var latestException: Throwable? = null
    val firstJob = async {
      runCatching {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } finally {
            withContext(NonCancellable) { releaseCleanup.await() }
            container.add(1)
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      val queuedJob = async {
        runCatching { loader.load { container.add(2) } }.exceptionOrNull()
      }.also { runCurrent() }
      val latestJob = launch {
        try {
          loader.load { container.add(3) }.getOrThrow()
        } catch (e: CancellationException) {
          latestException = e
          throw e
        }
      }.also { runCurrent() }

      latestJob.cancel(cause)
      runCurrent()
      assertEquals(true, latestJob.isCompleted)
      assertSame(cause, latestException)
      assertEquals(true, queuedJob.isCompleted)
      assertTrue(queuedJob.await() is FLoader.ReplacedCancellationException)
      assertEquals(false, firstJob.isCompleted)
      assertEquals(emptyList<Int>(), container)
      assertTrue(runCatching { loader.tryLoad { container.add(4) } }.exceptionOrNull() is FLoader.BusyCancellationException)

      releaseCleanup.complete(Unit)
      assertTrue(firstJob.await() is FLoader.ReplacedCancellationException)
      runCurrent()
      assertEquals(listOf(1), container)
      assertEquals(false, loader.isLoading())
      loader.tryLoad { container.add(4) }.getOrThrow()
      assertEquals(listOf(1, 4), container)
    } finally {
      releaseCleanup.complete(Unit)
    }
  }

  @Test
  fun `test load when caller already cancelled`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException()
    var container = ""
    var thrown: Throwable? = null

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
      currentCoroutineContext().cancel(cause)
      thrown = runCatching { loader.load { container += "2" } }.exceptionOrNull()
    }.also { cancelledJob ->
      runCurrent()
      assertSame(cause, thrown)
      assertEquals(true, cancelledJob.isCancelled)
      assertEquals(true, cancelledJob.isCompleted)
    }

    assertEquals(true, loader.isLoading())
    assertEquals(false, loadingJob.isCancelled)
    assertEquals("", container)

    loadingJob.cancelAndJoin()
    assertEquals("1", container)

    val idleCause = CustomCancellationException(2)
    launch {
      currentCoroutineContext().cancel(idleCause)
      thrown = runCatching { loader.load { container += "2" } }.exceptionOrNull()
    }.also { cancelledJob ->
      runCurrent()
      assertSame(idleCause, thrown)
      assertEquals(true, cancelledJob.isCancelled)
      assertEquals(true, cancelledJob.isCompleted)
    }
    assertEquals("1", container)
    assertEquals(false, loader.isLoading())
    assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
  }

  @Test
  fun `test tryLoad when caller already cancelled`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException()
    var container = ""
    var thrown: Throwable? = null

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
      currentCoroutineContext().cancel(cause)
      thrown = runCatching { loader.tryLoad { container += "2" } }.exceptionOrNull()
    }.also { cancelledJob ->
      runCurrent()
      assertSame(cause, thrown)
      assertEquals(true, cancelledJob.isCancelled)
      assertEquals(true, cancelledJob.isCompleted)
    }

    assertEquals(true, loader.isLoading())
    assertEquals(false, loadingJob.isCancelled)
    assertEquals("", container)

    loadingJob.cancelAndJoin()
    assertEquals("1", container)

    // 空闲时已取消的调用方也不会执行 onLoad
    val idleCause = CustomCancellationException(2)
    launch {
      currentCoroutineContext().cancel(idleCause)
      thrown = runCatching { loader.tryLoad { container += "2" } }.exceptionOrNull()
    }.also { cancelledJob ->
      runCurrent()
      assertSame(idleCause, thrown)
      assertEquals(true, cancelledJob.isCancelled)
      assertEquals(true, cancelledJob.isCompleted)
    }
    assertEquals("1", container)
    assertEquals(false, loader.isLoading())
    assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
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

  @Test(timeout = 20_000)
  fun `test concurrent load and tryLoad with error in block on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(100) {
        val loader = FLoader()
        val start = CompletableDeferred<Unit>()
        val failures = AtomicInteger()
        val running = AtomicInteger()
        val overlapped = AtomicBoolean()
        val onLoad: suspend () -> Unit = {
          if (running.incrementAndGet() != 1) overlapped.set(true)
          try {
            yield()
            throw BusinessException(5)
          } finally {
            running.decrementAndGet()
          }
        }

        coroutineScope {
          List(8) { index ->
            async {
              start.await()
              try {
                val result = if (index % 2 == 0) loader.tryLoad(onLoad) else loader.load(onLoad)
                // 执行到的任务只能以 Result.failure 结束
                assertTrue(result.exceptionOrNull() is BusinessException)
                failures.incrementAndGet()
              } catch (_: CancellationException) {
                // 忙状态或被后续任务取消属于预期行为
              }
            }
          }.also { jobs ->
            start.complete(Unit)
            jobs.awaitAll()
          }
        }

        // 以普通异常结束的任务必须释放锁和忙状态
        assertEquals(true, failures.get() > 0)
        assertEquals(false, overlapped.get())
        assertEquals(0, running.get())
        assertEquals(false, loader.isLoading())
        assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
      }
    }
  }

  @Test(timeout = 60_000)
  fun `test concurrent load with random caller cancellation on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(200) { round ->
        val loader = FLoader()
        val start = CompletableDeferred<Unit>()
        val running = AtomicInteger()
        val overlapped = AtomicBoolean()
        val causes = ConcurrentLinkedQueue<CancellationException>()
        val onLoad: suspend () -> Unit = {
          if (running.incrementAndGet() != 1) overlapped.set(true)
          try {
            yield()
          } finally {
            running.decrementAndGet()
          }
        }

        coroutineScope {
          val callerJobs = List(12) { index ->
            launch {
              start.await()
              try {
                if (index % 3 == 0) loader.tryLoad(onLoad) else loader.load(onLoad)
              } catch (e: CancellationException) {
                causes.add(e)
              }
            }
          }
          // 排队、替换和 cancelAndJoin 并发期间随机取消调用方
          val cancellerJobs = List(4) {
            launch {
              start.await()
              repeat(3) {
                yield()
                callerJobs[Random.nextInt(callerJobs.size)].cancel(CustomCancellationException(round))
              }
            }
          }
          if (round % 2 == 0) {
            launch {
              start.await()
              yield()
              loader.cancelAndJoin()
            }
          }
          start.complete(Unit)
          (callerJobs + cancellerJobs).joinAll()
        }

        // 调用方被取消后不能留下忙状态，也不能让其他任务重叠执行
        assertEquals(false, overlapped.get())
        assertEquals(0, running.get())
        assertEquals(false, loader.isLoading())
        assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
        causes.forEach { cause ->
          val expected = cause is FLoader.ManualCancellationException ||
            cause is FLoader.ReplacedCancellationException ||
            cause is FLoader.BusyCancellationException ||
            cause is CustomCancellationException
          assertTrue("unexpected cause: $cause", expected)
        }
      }
    }
  }

  @Test(timeout = 10_000)
  fun `test concurrent tryLoad accepts only one caller`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(100) {
        val loader = FLoader()
        val start = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val attempted = List(16) { CompletableDeferred<Unit>() }
        val entered = AtomicInteger()
        val loadJobs = attempted.map { attempt ->
          async {
            start.await()
            try {
              loader.tryLoad {
                entered.incrementAndGet()
                attempt.complete(Unit)
                releaseLoad.await()
                1
              }.getOrThrow()
            } catch (_: FLoader.BusyCancellationException) {
              attempt.complete(Unit)
              null
            }
          }
        }

        try {
          start.complete(Unit)
          // 成功任务保持加载，其余调用必须立即判忙，不能等待它结束
          withTimeout(5_000) { attempted.awaitAll() }
          assertEquals(1, entered.get())
          assertEquals(true, loader.isLoading())
        } finally {
          releaseLoad.complete(Unit)
        }

        val results = loadJobs.awaitAll()
        assertEquals(1, results.count { it == 1 })
        assertEquals(15, results.count { it == null })
        assertEquals(false, loader.isLoading())
        assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
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

        try {
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
        } finally {
          release.complete(Unit)
        }
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

          // tryLoad 只能成功或被并发的 cancelAndJoin 手动取消
          val exception = tryLoadJob.await()
          if (exception != null && exception !is FLoader.ManualCancellationException) throw exception
        }
      }
    }
  }

  @Test
  fun `test concurrent cancelAndJoin and tryLoad when busy`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(100) {
        val loader = FLoader()
        val started = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val running = AtomicInteger()
        val overlapped = AtomicBoolean()
        val first = async {
          runCatching {
            loader.load {
              running.incrementAndGet()
              started.complete(Unit)
              try {
                delay(Long.MAX_VALUE)
              } finally {
                try {
                  withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                  }
                } finally {
                  running.decrementAndGet()
                }
              }
            }.getOrThrow()
          }.exceptionOrNull()
        }

        try {
          started.await()
          coroutineScope {
            val start = CompletableDeferred<Unit>()
            val cancelJobs = List(8) {
              async {
                start.await()
                loader.cancelAndJoin()
              }
            }
            val tryLoadJobs = List(16) {
              async {
                start.await()
                runCatching {
                  loader.tryLoad {
                    if (running.incrementAndGet() != 1) overlapped.set(true)
                    try {
                      yield()
                      1
                    } finally {
                      running.decrementAndGet()
                    }
                  }.getOrThrow()
                }.exceptionOrNull()
              }
            }
            val cleanup = launch {
              start.await()
              cleanupStarted.await()
              releaseCleanup.complete(Unit)
            }

            start.complete(Unit)
            tryLoadJobs.awaitAll().forEach { cause ->
              val expected = cause == null || cause is FLoader.BusyCancellationException || cause is FLoader.ManualCancellationException
              assertTrue("unexpected cause: $cause", expected)
            }
            cancelJobs.awaitAll()
            cleanup.join()
          }

          assertTrue(first.await() is FLoader.ManualCancellationException)
          assertEquals(false, overlapped.get())
          assertEquals(0, running.get())
          assertEquals(false, loader.isLoading())
          assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
        } finally {
          releaseCleanup.complete(Unit)
        }
      }
    }
  }

  @Test
  fun `test cancelAndJoin when caller cancelled while waiting cleanup`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    var cancelException: Throwable? = null
    val firstJob = async {
      runCatching {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } finally {
            withContext(NonCancellable) {
              cleanupStarted.complete(Unit)
              releaseCleanup.await()
            }
            container.add("old-cleaned")
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      val cancelJob = launch {
        try {
          loader.cancelAndJoin()
        } catch (e: CancellationException) {
          cancelException = e
          throw e
        }
      }.also { runCurrent() }
      assertEquals(true, cleanupStarted.isCompleted)
      assertEquals(false, cancelJob.isCompleted)

      val nextJob = async {
        loader.load {
          container.add("new-load")
          2
        }
      }.also { runCurrent() }
      cancelJob.cancel(cause)
      runCurrent()

      assertEquals(true, cancelJob.isCancelled)
      assertEquals(true, cancelJob.isCompleted)
      assertSame(cause, cancelException)
      assertEquals(false, firstJob.isCompleted)
      assertEquals(false, nextJob.isCompleted)
      assertEquals(true, loader.isLoading())
      assertEquals(emptyList<String>(), container)
      assertTrue(runCatching { loader.tryLoad { 3 } }.exceptionOrNull() is FLoader.BusyCancellationException)

      releaseCleanup.complete(Unit)
      assertTrue(firstJob.await() is FLoader.ManualCancellationException)
      assertEquals(2, nextJob.await().getOrThrow())
      assertEquals(listOf("old-cleaned", "new-load"), container)
      assertEquals(false, loader.isLoading())
      assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
    } finally {
      releaseCleanup.complete(Unit)
    }
  }

  @Test
  fun `test cancelAndJoin with NonCancellable waits for cleanup when caller already cancelled`() = runTest {
    val loader = FLoader()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    val firstJob = async {
      runCatching {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } finally {
            withContext(NonCancellable) {
              cleanupStarted.complete(Unit)
              releaseCleanup.await()
            }
            container.add("old-cleaned")
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      val queuedJob = async {
        runCatching { loader.load { container.add("queued-load") } }.exceptionOrNull()
      }.also { runCurrent() }
      assertEquals(true, cleanupStarted.isCompleted)
      assertEquals(false, queuedJob.isCompleted)

      val cancelJob = launch {
        currentCoroutineContext().cancel()
        withContext(NonCancellable) {
          loader.cancelAndJoin()
          container.add("cancel-finished")
        }
      }.also { runCurrent() }

      assertEquals(true, queuedJob.isCompleted)
      assertTrue(queuedJob.await() is FLoader.ManualCancellationException)
      assertEquals(true, cancelJob.isCancelled)
      assertEquals(false, cancelJob.isCompleted)
      assertEquals(false, firstJob.isCompleted)
      assertEquals(true, loader.isLoading())
      assertEquals(emptyList<String>(), container)

      releaseCleanup.complete(Unit)
      cancelJob.join()
      assertTrue(firstJob.await() is FLoader.ReplacedCancellationException)
      assertEquals(listOf("old-cleaned", "cancel-finished"), container)
      assertEquals(false, loader.isLoading())
      assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
    } finally {
      releaseCleanup.complete(Unit)
    }
  }

  @Test
  fun `test load other loader in NonCancellable cleanup when caller cancelled`() = runTest {
    val loader = FLoader()
    val otherLoader = FLoader()
    var cleanupResult: Int? = null

    val job = launch {
      try {
        loader.load { delay(Long.MAX_VALUE) }
      } finally {
        // 调用方已取消，NonCancellable 中的加载仍正常执行并返回结果
        withContext(NonCancellable) {
          cleanupResult = otherLoader.load { 1 }.getOrThrow()
        }
      }
    }.also { runCurrent() }

    job.cancelAndJoin()
    assertEquals(1, cleanupResult)
    assertEquals(false, loader.isLoading())
    assertEquals(false, otherLoader.isLoading())
    assertEquals(2, otherLoader.tryLoad { 2 }.getOrThrow())
  }

  @Test
  fun `test load in NonCancellable cancelled by cancelAndJoin`() = runTest {
    checkLoadInNonCancellableCancelled(useTryLoad = false, replace = false)
  }

  @Test
  fun `test load in NonCancellable cancelled by new load`() = runTest {
    checkLoadInNonCancellableCancelled(useTryLoad = false, replace = true)
  }

  @Test
  fun `test tryLoad in NonCancellable cancelled by cancelAndJoin`() = runTest {
    checkLoadInNonCancellableCancelled(useTryLoad = true, replace = false)
  }

  @Test
  fun `test tryLoad in NonCancellable cancelled by new load`() = runTest {
    checkLoadInNonCancellableCancelled(useTryLoad = true, replace = true)
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
    assertEquals(true, loadJob.await() is FLoader.ManualCancellationException)
    assertEquals("", container)

    advanceUntilIdle()
    assertEquals("1", container)
    assertEquals(false, loader.isLoading())

    // 之后发起的加载不受影响
    loader.load { container += "3" }.getOrThrow()
    assertEquals("13", container)
  }

  @Test
  fun `test cancelAndJoin when caller already cancelled and multiple loads called`() = runTest {
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

    // 第二个 load 进入时取消第一个，并接替它等待旧任务清理
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

    // 第一个 load 被第二个替换，第二个 load 被 cancelAndJoin 取消
    assertEquals(true, loadJobs[0].await() is FLoader.ReplacedCancellationException)
    assertEquals(true, loadJobs[1].await() is FLoader.ManualCancellationException)
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
    // 旧任务必须先清理，取消方和新加载的恢复顺序不限
    assertTrue("Unexpected order: $container", container == "123" || container == "132")
    assertEquals(false, loader.isLoading())
  }

  @Test(timeout = 10_000)
  fun `test cancelAndJoin returns while later load remains running`() = runTest {
    val loader = FLoader()
    val cancelScheduler = TestCoroutineScheduler()
    val cancelDispatcher = StandardTestDispatcher(cancelScheduler)
    val releaseCleanup = CompletableDeferred<Unit>()
    val releaseLoad = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    val firstJob = async {
      runCatching {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } finally {
            withContext(NonCancellable) { releaseCleanup.await() }
            container.add("old-cleaned")
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    // 使用独立调度器，让取消方在新加载开始后恢复
    val cancelJob = launch(cancelDispatcher + cancelScheduler) {
      loader.cancelAndJoin()
      container.add("cancel-finished")
    }
    try {
      cancelScheduler.runCurrent()
      runCurrent()
      assertEquals(false, cancelJob.isCompleted)
      assertEquals(false, firstJob.isCompleted)

      val nextJob = async {
        loader.load {
          container.add("new-load")
          releaseLoad.await()
          2
        }.getOrThrow()
      }.also { runCurrent() }
      assertEquals(emptyList<String>(), container)

      releaseCleanup.complete(Unit)
      runCurrent()
      assertTrue(firstJob.await() is FLoader.ManualCancellationException)
      assertEquals(listOf("old-cleaned", "new-load"), container)
      assertEquals(false, cancelJob.isCompleted)
      assertEquals(true, loader.isLoading())

      // 新加载仍在执行，cancelAndJoin 只需等待调用时的任务
      cancelScheduler.runCurrent()
      assertEquals(true, cancelJob.isCompleted)
      assertEquals(listOf("old-cleaned", "new-load", "cancel-finished"), container)
      assertEquals(false, nextJob.isCompleted)
      assertEquals(false, nextJob.isCancelled)
      assertEquals(true, loader.isLoading())

      releaseLoad.complete(Unit)
      assertEquals(2, nextJob.await())
      assertEquals(false, loader.isLoading())
      assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
    } finally {
      releaseCleanup.complete(Unit)
      releaseLoad.complete(Unit)
      runCurrent()
      cancelScheduler.runCurrent()
    }
  }

  @Test(timeout = 10_000)
  fun `test cancelAndJoin cancels queued load resumed after previous finished`() = runTest {
    val loader = FLoader()
    val queuedScheduler = TestCoroutineScheduler()
    val queuedDispatcher = StandardTestDispatcher(queuedScheduler)
    var queuedEntered = false
    val firstJob = async {
      runCatching { loader.load { delay(Long.MAX_VALUE) } }.exceptionOrNull()
    }.also { runCurrent() }

    // 使用独立调度器，让排队任务在旧任务结束后仍停在恢复前
    val queuedJob = async(queuedDispatcher + queuedScheduler) {
      runCatching { loader.load { queuedEntered = true } }.exceptionOrNull()
    }
    try {
      queuedScheduler.runCurrent()
      runCurrent()
      assertTrue(firstJob.await() is FLoader.ReplacedCancellationException)
      assertEquals(false, queuedJob.isCompleted)

      val cancelJob = launch { loader.cancelAndJoin() }.also { runCurrent() }
      assertEquals(false, cancelJob.isCompleted)

      // 排队任务恢复后发现自己已被取消，不能执行回调
      queuedScheduler.runCurrent()
      runCurrent()
      assertTrue(queuedJob.await() is FLoader.ManualCancellationException)
      assertEquals(false, queuedEntered)
      assertEquals(true, cancelJob.isCompleted)
      assertEquals(false, loader.isLoading())
      assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
    } finally {
      queuedScheduler.runCurrent()
      runCurrent()
    }
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
        val callbackCause = AtomicReference<CancellationException>()

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
        try {
          started.await()

          val loadJob = async {
            runCatching {
              loader.load {
                try {
                  releaseLoad.await()
                  loaded.set(true)
                } catch (e: CancellationException) {
                  callbackCause.set(e)
                  throw e
                }
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
          assertTrue(loadJob.await() is FLoader.ManualCancellationException)
          callbackCause.get()?.also { assertTrue(it is FLoader.ManualCancellationException) }
          assertEquals(false, loaded.get())
          firstJob.join()
          assertEquals(false, loader.isLoading())
        } finally {
          releaseCleanup.complete(Unit)
          releaseLoad.complete(Unit)
        }
      }
    }
  }

  @Test
  fun `test concurrent load cancellation causes on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(100) {
        val loader = FLoader()
        val started = CompletableDeferred<Unit>()
        val causes = ConcurrentLinkedQueue<CancellationException>()

        val firstJob = launch {
          try {
            loader.load {
              started.complete(Unit)
              delay(Long.MAX_VALUE)
            }.getOrThrow()
          } catch (e: CancellationException) {
            causes.add(e)
          }
        }
        started.await()

        coroutineScope {
          val start = CompletableDeferred<Unit>()
          val loadJobs = List(8) {
            async {
              start.await()
              try {
                loader.load { yield() }.getOrThrow()
              } catch (e: CancellationException) {
                causes.add(e)
              }
            }
          }
          start.complete(Unit)
          loadJobs.awaitAll()
        }

        firstJob.join()
        assertTrue("expected at least one replaced load", causes.isNotEmpty())
        // 并发下被取消的 load 收到的必须是 ReplacedCancellationException
        causes.forEach { cause ->
          assertTrue("unexpected cause: $cause", cause is FLoader.ReplacedCancellationException)
        }
        assertEquals(false, loader.isLoading())
        loader.load { }.getOrThrow()
      }
    }
  }

  @Test
  fun `test concurrent cancelAndJoin and load cancellation causes on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(100) {
        val loader = FLoader()
        val started = CompletableDeferred<Unit>()
        val causes = ConcurrentLinkedQueue<CancellationException>()

        val firstJob = launch {
          try {
            loader.load {
              started.complete(Unit)
              delay(Long.MAX_VALUE)
            }.getOrThrow()
          } catch (e: CancellationException) {
            causes.add(e)
          }
        }
        // 等首个加载运行后再并发发起替换和手动取消
        started.await()

        coroutineScope {
          val start = CompletableDeferred<Unit>()
          val loadJobs = List(4) {
            async {
              start.await()
              try {
                loader.load { yield() }.getOrThrow()
              } catch (e: CancellationException) {
                causes.add(e)
              }
            }
          }
          launch {
            start.await()
            loader.cancelAndJoin()
          }
          start.complete(Unit)
          loadJobs.awaitAll()
        }

        firstJob.join()
        assertTrue("expected at least one cancelled load", causes.isNotEmpty())
        // 取消原因只能是手动取消或被新加载替换
        causes.forEach { cause ->
          val expected = cause is FLoader.ManualCancellationException ||
            cause is FLoader.ReplacedCancellationException
          assertTrue("unexpected cause: $cause", expected)
        }
        assertEquals(false, loader.isLoading())
        loader.load { }.getOrThrow()
      }
    }
  }

  @Test(timeout = 10_000)
  fun `test concurrent load on Unconfined dispatcher`() = runTest {
    withContext(Dispatchers.Unconfined) {
      repeat(100) {
        val loader = FLoader()
        val running = AtomicInteger()
        val started = AtomicInteger()
        val overlapped = AtomicBoolean()
        val causes = ConcurrentLinkedQueue<CancellationException>()

        // Unconfined 下被取消任务的清理会在 load 调用线程上内联执行
        coroutineScope {
          List(8) {
            async {
              try {
                loader.load {
                  if (running.incrementAndGet() != 1) overlapped.set(true)
                  started.incrementAndGet()
                  try {
                    yield()
                    delay(1)
                  } finally {
                    running.decrementAndGet()
                  }
                }.getOrThrow()
              } catch (e: CancellationException) {
                causes.add(e)
              }
            }
          }.awaitAll()
        }

        assertEquals(false, overlapped.get())
        assertEquals(0, running.get())
        assertEquals(true, started.get() > 0)
        causes.forEach { cause ->
          assertTrue("unexpected cause: $cause", cause is FLoader.ReplacedCancellationException)
        }
        assertEquals(false, loader.isLoading())
        assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
      }
    }
  }

  @Test(timeout = 10_000)
  fun `test load from replaced caller finally on Unconfined`() = runTest {
    val loader = FLoader()
    val container = mutableListOf<String>()
    val firstJob = launch(Dispatchers.Unconfined) {
      try {
        loader.load { delay(Long.MAX_VALUE) }
      } finally {
        // 旧任务的清理在新 load 的取消调用内联执行，这里发起的 load 会反过来替换那个新 load
        loader.load { container.add("finally-load") }.getOrThrow()
      }
    }.also { runCurrent() }

    val exception = runCatching { loader.load { container.add("new-load") } }.exceptionOrNull()
    assertTrue(exception is FLoader.ReplacedCancellationException)
    assertEquals(true, firstJob.isCompleted)
    assertEquals(listOf("finally-load"), container)
    assertEquals(false, loader.isLoading())
    assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
  }

  @Test(timeout = 10_000)
  fun `test cancelAndJoin from replaced caller finally on Unconfined`() = runTest {
    val loader = FLoader()
    val container = mutableListOf<String>()
    val firstJob = launch(Dispatchers.Unconfined) {
      try {
        loader.load { delay(Long.MAX_VALUE) }
      } finally {
        // 旧任务的清理在新 load 的取消调用内联执行，这里的 cancelAndJoin 会取消那个已进入的新 load
        loader.cancelAndJoin()
        container.add("cancel-finished")
      }
    }.also { runCurrent() }

    val exception = runCatching { loader.load { container.add("new-load") } }.exceptionOrNull()
    assertTrue(exception is FLoader.ManualCancellationException)
    assertEquals(true, firstJob.isCompleted)
    assertEquals(listOf("cancel-finished"), container)
    assertEquals(false, loader.isLoading())
    assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
  }

  @Test(timeout = 10_000)
  fun `test tryLoad from replaced caller finally on Unconfined`() = runTest {
    val loader = FLoader()
    val container = mutableListOf<String>()
    var tryLoadCause: Throwable? = null
    val firstJob = launch(Dispatchers.Unconfined) {
      try {
        loader.load { delay(Long.MAX_VALUE) }
      } finally {
        // 旧任务的清理在新 load 的取消调用内联执行，此时新 load 已登记但尚未执行，tryLoad 必须立即判忙
        tryLoadCause = runCatching { loader.tryLoad { container.add("finally-tryLoad") } }.exceptionOrNull()
      }
    }.also { runCurrent() }

    assertEquals(2, loader.load {
      container.add("new-load")
      2
    }.getOrThrow())
    assertTrue(tryLoadCause is FLoader.BusyCancellationException)
    assertEquals(true, firstJob.isCompleted)
    assertEquals(listOf("new-load"), container)
    assertEquals(false, loader.isLoading())
    assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
  }

  @Test(timeout = 10_000)
  fun `test load from replaced queued caller finally on Unconfined`() = runTest {
    val loader = FLoader()
    val container = mutableListOf<String>()
    launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          withContext(NonCancellable) { delay(1000) }
          container.add("old-cleaned")
        }
      }
    }.also { runCurrent() }

    val queuedJob = launch(Dispatchers.Unconfined) {
      try {
        loader.load { container.add("queued-load") }
      } finally {
        // 排队任务在新 load 的取消调用内联结束，这里发起的 load 会反过来替换那个新 load，并接替等待旧任务清理
        loader.load { container.add("finally-load") }.getOrThrow()
      }
    }.also { runCurrent() }

    val startTime = currentTime
    val exception = runCatching { loader.load { container.add("new-load") } }.exceptionOrNull()
    assertTrue(exception is FLoader.ReplacedCancellationException)
    assertEquals(startTime, currentTime)
    assertEquals(false, queuedJob.isCompleted)
    assertEquals(emptyList<String>(), container)

    advanceUntilIdle()
    assertEquals(true, queuedJob.isCompleted)
    assertEquals(listOf("old-cleaned", "finally-load"), container)
    assertEquals(startTime + 1000, currentTime)
    assertEquals(false, loader.isLoading())
    assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
  }

  @Test(timeout = 10_000)
  fun `test load from cancelled queued caller finally on Unconfined`() = runTest {
    val loader = FLoader()
    val container = mutableListOf<String>()
    var queuedException: Throwable? = null
    launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          withContext(NonCancellable) { delay(1000) }
          container.add("old-cleaned")
        }
      }
    }.also { runCurrent() }

    val queuedJob = launch(Dispatchers.Unconfined) {
      try {
        loader.load { container.add("queued-load") }
      } catch (e: CancellationException) {
        queuedException = e
        throw e
      } finally {
        // 排队任务在 cancelAndJoin 的取消调用内联结束，这里发起的 load 不受该 cancelAndJoin 影响，等旧任务清理后执行
        loader.load { container.add("finally-load") }.getOrThrow()
      }
    }.also { runCurrent() }

    val startTime = currentTime
    loader.cancelAndJoin()
    container.add("cancel-finished")
    assertTrue(queuedException is FLoader.ManualCancellationException)
    assertEquals(startTime + 1000, currentTime)

    advanceUntilIdle()
    assertEquals(true, queuedJob.isCompleted)
    // 旧任务必须先清理，取消方和 finally 内加载的恢复顺序不限
    assertEquals("old-cleaned", container.first())
    assertEquals(setOf("old-cleaned", "finally-load", "cancel-finished"), container.toSet())
    assertEquals(3, container.size)
    assertEquals(false, loader.isLoading())
    assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
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
  fun `test cancelAndJoin returns when caller already cancelled and loader idle`() = runTest {
    val loader = FLoader()
    var returned = false
    val caller = launch {
      currentCoroutineContext().cancel()
      loader.cancelAndJoin()
      returned = true
    }
    caller.join()

    assertEquals(true, caller.isCancelled)
    assertEquals(true, returned)
    assertEquals(false, loader.isLoading())
    assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
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
  fun `test stateFlow when cancel`() = runTest {
    val loader = FLoader()
    // 直接收集 stateFlow，不经过 loadingFlow 的映射和去重
    loader.stateFlow.test {
      assertEquals(FLoader.State(isLoading = false), awaitItem())
      val job = launch { loader.load { delay(Long.MAX_VALUE) } }.also { runCurrent() }
      assertEquals(FLoader.State(isLoading = true), awaitItem())
      loader.cancelAndJoin()
      assertEquals(FLoader.State(isLoading = false), awaitItem())
      assertEquals(true, job.isCancelled)
    }
  }

  @Test
  fun `test safeRunCatching`() = runTest {
    safeRunCatching { 1 }.also { result ->
      assertEquals(1, result.getOrThrow())
    }

    val cause = BusinessException(4)
    safeRunCatching { throw cause }.also { result ->
      assertSame(cause, result.exceptionOrNull())
    }

    val error = AssertionError("block failed")
    safeRunCatching { throw error }.also { result ->
      assertSame(error, result.exceptionOrNull())
    }

    val cancellation = CustomCancellationException()
    runCatching {
      safeRunCatching { throw cancellation }
    }.also { result ->
      assertSame(cancellation, result.exceptionOrNull())
    }
  }

  private suspend fun TestScope.checkOtherLoaderCancelledInBlock(replace: Boolean) {
    val loader = FLoader()
    val otherLoader = FLoader()
    var exceptionInBlock: Throwable? = null
    var loadException: Throwable? = null

    val job = launch {
      loadException = runCatching {
        loader.load {
          try {
            otherLoader.load { delay(Long.MAX_VALUE) }
          } catch (e: CancellationException) {
            exceptionInBlock = e
            throw e
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }
    assertEquals(true, loader.isLoading())
    assertEquals(true, otherLoader.isLoading())

    if (replace) {
      otherLoader.load { 2 }.also { assertEquals(2, it.getOrThrow()) }
    } else {
      otherLoader.cancelAndJoin()
    }
    job.join()

    // 内层 Loader 被别处取消时，外层 load 原样抛出内层的取消异常，而不是返回 Result.failure
    val expectedType = if (replace) FLoader.ReplacedCancellationException::class else FLoader.ManualCancellationException::class
    assertEquals(expectedType, exceptionInBlock!!::class)
    assertEquals(expectedType, loadException!!::class)
    assertEquals(false, loader.isLoading())
    assertEquals(false, otherLoader.isLoading())
    assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
    assertEquals(4, otherLoader.tryLoad { 4 }.getOrThrow())
  }

  private suspend fun TestScope.checkOtherCancellationAfterCancelled(replace: Boolean) {
    val loader = FLoader()
    var exceptionInBlock: Throwable? = null
    val job = async {
      runCatching {
        loader.load {
          try {
            delay(Long.MAX_VALUE)
          } catch (e: CancellationException) {
            exceptionInBlock = e
            throw CustomCancellationException(2)
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    if (replace) {
      loader.load { 2 }.also { assertEquals(2, it.getOrThrow()) }
    } else {
      loader.cancelAndJoin()
    }

    // 取消后 onLoad 抛出其他取消异常，仍保留最先的取消原因
    val expectedType = if (replace) FLoader.ReplacedCancellationException::class else FLoader.ManualCancellationException::class
    assertEquals(expectedType, exceptionInBlock!!::class)
    assertEquals(expectedType, job.await()!!::class)
    assertEquals(false, loader.isLoading())
    assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
  }

  // load 与 tryLoad 的登记路径不同，需分别验证调用方取消后仍占住 Loader 直到清理结束
  private suspend fun TestScope.checkLoadAfterCallerCancelledCleanup(useTryLoad: Boolean) {
    val loader = FLoader()
    val cause = CustomCancellationException()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    var exceptionInBlock: Throwable? = null
    var loadException: Throwable? = null
    val onLoad: suspend () -> Unit = {
      try {
        delay(Long.MAX_VALUE)
      } catch (e: CancellationException) {
        exceptionInBlock = e
        throw e
      } finally {
        withContext(NonCancellable) { releaseCleanup.await() }
        container.add("old-cleaned")
      }
    }
    val firstJob = launch {
      loadException = runCatching {
        if (useTryLoad) loader.tryLoad(onLoad) else loader.load(onLoad)
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      firstJob.cancel(cause)
      runCurrent()
      assertSame(cause, exceptionInBlock)
      assertEquals(false, firstJob.isCompleted)
      assertEquals(true, loader.isLoading())

      // 调用方已取消但旧任务仍在清理，新的 load 必须等待，tryLoad 立即判忙
      val nextJob = async {
        loader.load {
          container.add("new-load")
          2
        }
      }.also { runCurrent() }
      assertEquals(false, nextJob.isCompleted)
      assertEquals(emptyList<String>(), container)
      assertTrue(runCatching { loader.tryLoad { 3 } }.exceptionOrNull() is FLoader.BusyCancellationException)

      releaseCleanup.complete(Unit)
      assertEquals(2, nextJob.await().getOrThrow())
      firstJob.join()
      // 先被调用方取消再被新的 load 替换的加载保留调用方的取消原因
      assertSame(cause, loadException)
      assertEquals(true, firstJob.isCancelled)
      assertEquals(listOf("old-cleaned", "new-load"), container)
      assertEquals(false, loader.isLoading())
      assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
    } finally {
      releaseCleanup.complete(Unit)
    }
  }

  // load 与 tryLoad 的登记路径不同，需分别验证 NonCancellable 内仍会被取消
  private suspend fun TestScope.checkLoadInNonCancellableCancelled(useTryLoad: Boolean, replace: Boolean) {
    val loader = FLoader()
    val container = mutableListOf<String>()
    var exceptionInBlock: Throwable? = null
    var loadException: Throwable? = null
    val onLoad: suspend () -> Unit = {
      try {
        delay(Long.MAX_VALUE)
      } catch (e: CancellationException) {
        exceptionInBlock = e
        throw e
      } finally {
        container.add("cleaned")
      }
    }

    val job = launch {
      withContext(NonCancellable) {
        loadException = runCatching {
          if (useTryLoad) loader.tryLoad(onLoad) else loader.load(onLoad)
        }.exceptionOrNull()
      }
    }.also { runCurrent() }
    assertEquals(true, loader.isLoading())

    if (replace) {
      loader.load {
        container.add("new-load")
        2
      }.also { assertEquals(2, it.getOrThrow()) }
    } else {
      loader.cancelAndJoin()
    }
    job.join()

    // NonCancellable 只阻止调用方取消，加载仍会被 cancelAndJoin 或新的 load 取消
    val expectedType = if (replace) FLoader.ReplacedCancellationException::class else FLoader.ManualCancellationException::class
    assertEquals(expectedType, exceptionInBlock!!::class)
    assertEquals(expectedType, loadException!!::class)
    assertEquals(false, job.isCancelled)
    assertEquals(if (replace) listOf("cleaned", "new-load") else listOf("cleaned"), container)
    assertEquals(false, loader.isLoading())
    assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
  }
}
