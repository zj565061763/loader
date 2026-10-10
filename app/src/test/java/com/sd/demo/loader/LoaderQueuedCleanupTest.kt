package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class LoaderQueuedCleanupTest(private val useTryLoad: Boolean) {

  private class CustomCancellationException : CancellationException("custom cause")

  @Test
  fun `test tryLoad when queued caller cancelled with custom cause during previous cleanup`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException()
    var container = ""
    var cleanupStarted = false
    var queuedException: Throwable? = null

    val loadingJob = launch {
      loader.loadForTest {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          withContext(NonCancellable) {
            cleanupStarted = true
            delay(1000)
          }
          container += "1"
        }
      }
    }.also { runCurrent() }

    val queuedJob = launch {
      try {
        loader.load { container += "2" }
      } catch (e: CancellationException) {
        queuedException = e
        throw e
      }
    }.also { runCurrent() }
    val startTime = currentTime
    queuedJob.cancel(cause)
    runCurrent()
    // 排队任务已取消并完成，旧任务仍在清理
    assertSame(cause, queuedException)
    assertEquals(true, queuedJob.isCancelled)
    assertEquals(true, queuedJob.isCompleted)
    assertEquals(true, cleanupStarted)
    assertEquals(false, loadingJob.isCompleted)
    assertEquals("", container)
    assertEquals(startTime, currentTime)

    runCatching { loader.tryLoad { container += "3" }.getOrThrow() }.also { result ->
      assertEquals(true, result.exceptionOrNull() is FLoader.BusyCancellationException)
    }
    assertEquals(startTime, currentTime)
    assertEquals("", container)

    advanceUntilIdle()
    assertEquals("1", container)
    assertEquals(false, loader.loadingFlow.value)
    loader.tryLoad { container += "3" }.getOrThrow()
    assertEquals("13", container)
  }

  @Test
  fun `test queued load timeout leaves previous cleanup running`() = runTest {
    val loader = FLoader()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    var queuedCause: Throwable? = null

    loader.loadingFlow.test {
      assertEquals(false, awaitItem())
      val loading = launch {
        loader.loadForTest {
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
      }.also { runCurrent() }

      try {
        assertEquals(true, awaitItem())
        val queued = async {
          runCatching {
            withTimeout(100) {
              try {
                loader.load { container.add("queued-load") }
              } catch (e: CancellationException) {
                queuedCause = e
                throw e
              }
            }
          }.exceptionOrNull()
        }.also { runCurrent() }
        assertEquals(true, cleanupStarted.isCompleted)
        assertEquals(false, queued.isCompleted)
        val startTime = currentTime

        advanceTimeBy(100)
        runCurrent()

        assertEquals(true, queued.isCompleted)
        assertTrue(queuedCause is TimeoutCancellationException)
        assertTrue(queued.await() is TimeoutCancellationException)
        assertEquals(false, loading.isCompleted)
        assertEquals(true, loader.loadingFlow.value)
        assertEquals(emptyList<String>(), container)
        assertEquals(startTime + 100, currentTime)
        assertTrue(runCatching { loader.tryLoad { container.add("try-load") } }.exceptionOrNull() is FLoader.BusyCancellationException)
        assertEquals(emptyList<String>(), container)
        assertEquals(startTime + 100, currentTime)
        expectNoEvents()

        releaseCleanup.complete(Unit)
        loading.join()
        assertEquals(listOf("old-cleaned"), container)
        assertEquals(false, awaitItem())
        assertEquals(false, loader.loadingFlow.value)
      } finally {
        releaseCleanup.complete(Unit)
        loading.cancelAndJoin()
      }
    }

    assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
  }

  @Test
  fun `test cancelAndJoin when queued caller cancelled during previous cleanup`() = runTest {
    val loader = FLoader()
    var container = ""
    var cleanupStarted = false

    val loadingJob = launch {
      loader.loadForTest {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          withContext(NonCancellable) {
            cleanupStarted = true
            delay(1000)
          }
          container += "1"
        }
      }
    }.also { runCurrent() }

    val queuedJob = launch { loader.load { container += "2" } }.also { runCurrent() }
    queuedJob.cancelAndJoin()
    assertEquals(true, queuedJob.isCompleted)
    assertEquals(true, cleanupStarted)
    assertEquals(false, loadingJob.isCompleted)
    assertEquals("", container)
    val startTime = currentTime

    // 排队任务结束后，cancelAndJoin 仍须等待旧任务清理
    val cancelJob = launch {
      loader.cancelAndJoin()
      container += "3"
    }.also { runCurrent() }
    assertEquals(false, cancelJob.isCompleted)
    assertEquals(startTime, currentTime)
    assertEquals("", container)

    cancelJob.join()
    assertEquals(true, loadingJob.isCompleted)
    assertEquals(startTime + 1000, currentTime)
    assertEquals("13", container)
    assertEquals(false, loader.loadingFlow.value)
    assertEquals(4, loader.tryLoad { 4 }.getOrThrow())
  }

  @Test
  fun `test load when queued caller cancelled during previous cleanup`() = runTest {
    val loader = FLoader()
    val container = mutableListOf<String>()
    var cleanupStarted = false

    val first = async {
      runCatching {
        loader.loadForTest {
          try {
            delay(Long.MAX_VALUE)
          } finally {
            withContext(NonCancellable) {
              cleanupStarted = true
              delay(1000)
            }
            container.add("old-cleaned")
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    val queuedJob = launch { loader.load { container.add("queued-load") } }.also { runCurrent() }
    queuedJob.cancelAndJoin()
    assertEquals(true, cleanupStarted)
    assertEquals(false, first.isCompleted)

    // 排队任务已取消，新的 load 仍须等待旧任务清理结束后执行一次
    val startTime = currentTime
    val next = async {
      loader.load {
        container.add("new-load")
        2
      }
    }.also { runCurrent() }
    assertEquals(false, next.isCompleted)
    assertEquals(true, loader.loadingFlow.value)
    assertEquals(emptyList<String>(), container)

    advanceUntilIdle()
    assertTrue(first.await() is FLoader.ReplacedCancellationException)
    assertEquals(2, next.await().getOrThrow())
    assertEquals(listOf("old-cleaned", "new-load"), container)
    assertEquals(startTime + 1000, currentTime)
    assertEquals(false, loader.loadingFlow.value)
    assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
  }

  @Test
  fun `test tryLoad and cancelAndJoin when queued loads cancelled repeatedly during previous cleanup`() = runTest {
    val loader = FLoader()
    var container = ""
    var cleanupStarted = false

    val loadingJob = launch {
      loader.loadForTest {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          withContext(NonCancellable) {
            cleanupStarted = true
            delay(1000)
          }
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    // 两个排队任务先后取消，旧任务仍在清理
    val queuedJob1 = launch { loader.load { container += "2" } }.also { runCurrent() }
    queuedJob1.cancelAndJoin()
    val queuedJob2 = launch { loader.load { container += "3" } }.also { runCurrent() }
    queuedJob2.cancelAndJoin()

    assertEquals(true, cleanupStarted)
    assertEquals(false, loadingJob.isCompleted)
    assertEquals("", container)

    // 排队任务都已取消，tryLoad 仍须因清理中的旧任务立即判忙
    val startTime = currentTime
    runCatching { loader.tryLoad { container += "4" } }.also { result ->
      assertEquals(true, result.exceptionOrNull() is FLoader.BusyCancellationException)
    }
    assertEquals(startTime, currentTime)
    assertEquals("", container)

    // cancelAndJoin 仍须等待旧任务清理结束
    val cancelJob = launch {
      loader.cancelAndJoin()
      container += "5"
    }.also { runCurrent() }
    assertEquals(false, cancelJob.isCompleted)

    cancelJob.join()
    assertEquals(true, loadingJob.isCompleted)
    assertEquals(startTime + 1000, currentTime)
    assertEquals("15", container)
    assertEquals(false, loader.loadingFlow.value)
    assertEquals(6, loader.tryLoad { 6 }.getOrThrow())
  }

  private suspend fun <T> FLoader.loadForTest(onLoad: suspend () -> T): Result<T> {
    return if (useTryLoad) tryLoad(onLoad) else load(onLoad)
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "tryLoad={0}")
    fun parameters(): List<Boolean> = listOf(false, true)
  }
}
