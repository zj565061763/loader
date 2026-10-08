package com.sd.demo.loader

import com.sd.lib.loader.FLoader
import com.sd.lib.loader.FMutator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class MutatorTest {

  private class CallerCancellationException(val code: Int) : CancellationException("caller cancelled: $code")

  @Test(timeout = 10_000)
  fun `test queued task cannot run after newer task registered before cancellation`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { replacementDispatcher ->
      val replacements = AtomicInteger()
      val newestRegistered = CompletableDeferred<Unit>()
      val allowNewestCancel = CountDownLatch(1)
      val cleanupStarted = CompletableDeferred<Unit>()
      val releaseCleanup = CompletableDeferred<Unit>()
      val queuedEntered = AtomicBoolean()
      val tryEntered = AtomicBoolean()
      val mutator = FMutator(
        newReplaceCause = {
          if (replacements.incrementAndGet() == 2) {
            // 暂停最新任务的取消操作，保留旧排队任务恢复执行的窗口
            newestRegistered.complete(Unit)
            check(allowNewestCancel.await(5, TimeUnit.SECONDS))
          }
          FLoader.ReplacedCancellationException()
        },
        newBusyCause = { FLoader.BusyCancellationException() },
      )

      val first = async {
        runCatching {
          mutator.mutate {
            try {
              delay(Long.MAX_VALUE)
            } finally {
              withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
              }
            }
          }
        }.exceptionOrNull()
      }.also { runCurrent() }

      try {
        val queued = async {
          runCatching { mutator.mutate { queuedEntered.set(true) } }.exceptionOrNull()
        }.also { runCurrent() }
        assertEquals(true, cleanupStarted.isCompleted)
        assertEquals(false, queued.isCompleted)

        val latest = async(replacementDispatcher) { mutator.mutate { 3 } }
        newestRegistered.await()
        releaseCleanup.complete(Unit)
        runCurrent()

        // 最新任务尚未发起取消，旧排队任务必须自行识别替换并退出
        assertEquals(false, queuedEntered.get())
        assertEquals(true, queued.isCompleted)
        assertTrue(queued.await() is FLoader.ReplacedCancellationException)
        assertTrue(first.await() is FLoader.ReplacedCancellationException)

        // 旧任务已结束，最新任务已登记但尚未执行，仍须立即判忙
        val startTime = currentTime
        val busyCause = runCatching { mutator.mutateOrThrow { tryEntered.set(true) } }.exceptionOrNull()
        assertTrue(busyCause is FLoader.BusyCancellationException)
        assertEquals(startTime, currentTime)
        assertEquals(false, tryEntered.get())
        assertEquals(false, latest.isCancelled)

        allowNewestCancel.countDown()
        assertEquals(3, latest.await())
        assertEquals(4, mutator.mutateOrThrow { 4 })
      } finally {
        allowNewestCancel.countDown()
        releaseCleanup.complete(Unit)
      }
    }
  }

  @Test(timeout = 10_000)
  fun `test cancelled caller cancels running task before registered replacement starts cancelling it`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { replacementDispatcher ->
      val registered = CompletableDeferred<Unit>()
      val allowReplacementCancel = CountDownLatch(1)
      val cleanupStarted = CompletableDeferred<Unit>()
      val releaseCleanup = CompletableDeferred<Unit>()
      val replacementEntered = AtomicBoolean()
      val mutator = FMutator(
        newCancelCause = { FLoader.ManualCancellationException() },
        newReplaceCause = {
          // 暂停首次替换的取消操作，让旧任务保持运行
          registered.complete(Unit)
          check(allowReplacementCancel.await(5, TimeUnit.SECONDS))
          FLoader.ReplacedCancellationException()
        },
      )
      var runningCause: CancellationException? = null
      var cancelCause: Throwable? = null
      val first = async {
        runCatching {
          mutator.mutate {
            try {
              delay(Long.MAX_VALUE)
            } catch (e: CancellationException) {
              runningCause = e
              throw e
            } finally {
              withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
              }
            }
          }
        }.exceptionOrNull()
      }.also { runCurrent() }
      val latest = async(replacementDispatcher) {
        runCatching { mutator.mutate { replacementEntered.set(true) } }.exceptionOrNull()
      }

      try {
        registered.await()
        assertEquals(false, cleanupStarted.isCompleted)
        val callerCause = CallerCancellationException(1)
        val canceller = launch {
          currentCoroutineContext().cancel(callerCause)
          cancelCause = runCatching { mutator.cancelAndJoin() }.exceptionOrNull()
        }.also { runCurrent() }

        assertEquals(true, canceller.isCompleted)
        assertSame(callerCause, cancelCause)
        assertEquals(true, cleanupStarted.isCompleted)
        assertTrue(runningCause is FLoader.ManualCancellationException)
        assertEquals(false, first.isCompleted)
        assertEquals(false, latest.isCompleted)
        assertEquals(false, replacementEntered.get())

        releaseCleanup.complete(Unit)
        assertTrue(first.await() is FLoader.ManualCancellationException)
        allowReplacementCancel.countDown()
        assertTrue(latest.await() is FLoader.ManualCancellationException)
        assertEquals(false, replacementEntered.get())
        assertEquals(3, mutator.mutateOrThrow { 3 })
      } finally {
        allowReplacementCancel.countDown()
        releaseCleanup.complete(Unit)
        first.cancelAndJoin()
        latest.cancelAndJoin()
      }
    }
  }
}
