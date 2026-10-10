// IDE 未识别跨模块 friendPaths，测试文件显式抑制内部成员的可见性诊断
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.sd.demo.loader

import com.sd.lib.loader.FLoader
import com.sd.lib.loader.FMutator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class MutatorTest {

  private class CallerCancellationException(val code: Int) : CancellationException("caller cancelled: $code")

  @Test(timeout = 10_000)
  fun `test context probe pauses registered tryLoad before callback`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { callerDispatcher ->
      val loader = FLoader()
      val callerPaused = CompletableDeferred<Unit>()
      val releaseCaller = CountDownLatch(1)
      val probePaused = AtomicBoolean()
      val callbackEntered = AtomicBoolean()
      val contextProbe = PauseOnRepeatedContextKey {
        probePaused.set(true)
        callerPaused.complete(Unit)
        check(releaseCaller.await(5, TimeUnit.SECONDS))
      }
      val caller = async(callerDispatcher + contextProbe) {
        contextProbe.arm()
        try {
          loader.tryLoad {
            callbackEntered.set(true)
            1
          }
        } finally {
          // 探针未触发时也唤醒断言，避免测试只能超时
          callerPaused.complete(Unit)
        }
      }

      try {
        callerPaused.await()
        assertEquals(true, probePaused.get())
        assertEquals(false, callbackEntered.get())
        assertEquals(false, caller.isCompleted)
        val busyCause = runCatching { loader.tryLoad { 2 } }.exceptionOrNull()
        assertTrue(busyCause is FLoader.BusyCancellationException)
      } finally {
        releaseCaller.countDown()
        caller.join()
      }

      assertEquals(1, caller.await().getOrThrow())
      assertEquals(true, callbackEntered.get())
      assertEquals(false, loader.isLoading())
      assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
    }
  }

  @Test(timeout = 10_000)
  fun `test registered tryLoad cancelled before callback`() = runTest {
    checkRegisteredTaskCancellation(useTryLoad = true, replace = false)
  }

  @Test(timeout = 10_000)
  fun `test registered tryLoad replaced before callback`() = runTest {
    checkRegisteredTaskCancellation(useTryLoad = true, replace = true)
  }

  @Test(timeout = 10_000)
  fun `test registered load cancelled before callback`() = runTest {
    checkRegisteredTaskCancellation(useTryLoad = false, replace = false)
  }

  @Test(timeout = 10_000)
  fun `test registered load replaced before callback`() = runTest {
    checkRegisteredTaskCancellation(useTryLoad = false, replace = true)
  }

  @Test(timeout = 10_000)
  fun `test already cancelled tryLoad does not make idle loader busy`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { callerDispatcher ->
      val loader = FLoader()
      val cause = CallerCancellationException(2)
      val callerPaused = CompletableDeferred<Unit>()
      val releaseCaller = CountDownLatch(1)
      val callbackEntered = AtomicBoolean()
      var thrown: Throwable? = null
      val contextProbe = PauseOnRepeatedContextKey {
        callerPaused.complete(Unit)
        check(releaseCaller.await(5, TimeUnit.SECONDS))
      }
      val caller = launch(callerDispatcher + contextProbe) {
        currentCoroutineContext().cancel(cause)
        contextProbe.arm()
        thrown = runCatching { loader.tryLoad { callbackEntered.set(true) } }.exceptionOrNull()
        // 入口检查直接退出时，也保持调用方暂停，让另一任务验证空闲状态
        callerPaused.complete(Unit)
        check(releaseCaller.await(5, TimeUnit.SECONDS))
      }

      try {
        callerPaused.await()
        assertEquals(false, caller.isCompleted)
        assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
      } finally {
        releaseCaller.countDown()
        caller.join()
      }

      assertSame(cause, thrown)
      assertEquals(false, callbackEntered.get())
      assertEquals(false, loader.isLoading())
      assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
    }
  }

  // 入口检查挪到登记之后时探针用例发现不了，这里靠并发的 tryLoad 撞上已取消调用方短暂登记的窗口
  @Test(timeout = 10_000)
  fun `test already cancelled tryLoad does not make idle loader busy on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      val loader = FLoader()
      val stop = AtomicBoolean()
      coroutineScope {
        repeat(4) {
          launch {
            // 不断以已取消的调用方调用 tryLoad
            while (!stop.get()) {
              runCatching {
                coroutineScope {
                  currentCoroutineContext().cancel()
                  loader.tryLoad { }
                }
              }
            }
          }
        }
        try {
          // 已取消的调用方不登记任务，正常的 tryLoad 不能被判忙
          repeat(20_000) {
            val cause = runCatching { loader.tryLoad { }.getOrThrow() }.exceptionOrNull()
            assertEquals(null, cause)
          }
        } finally {
          stop.set(true)
        }
      }
    }
  }

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
        newCancelCause = { FLoader.ManualCancellationException() },
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
  fun `test task without running task cannot run after newer task registered before cancellation`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { olderDispatcher ->
      Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { newestDispatcher ->
        val queuedScheduler = TestCoroutineScheduler()
        val queuedDispatcher = StandardTestDispatcher(queuedScheduler)
        val replacements = AtomicInteger()
        val olderRegistered = CompletableDeferred<Unit>()
        val allowOlderCancel = CountDownLatch(1)
        val newestRegistered = CompletableDeferred<Unit>()
        val allowNewestCancel = CountDownLatch(1)
        val queuedEntered = AtomicBoolean()
        val olderEntered = AtomicBoolean()
        val mutator = FMutator(
          newCancelCause = { FLoader.ManualCancellationException() },
          newReplaceCause = {
            when (replacements.incrementAndGet()) {
              2 -> {
                // 较旧任务已登记，暂停它对排队任务的取消
                olderRegistered.complete(Unit)
                check(allowOlderCancel.await(5, TimeUnit.SECONDS))
              }
              3 -> {
                // 最新任务已登记，暂停它对较旧任务的取消
                newestRegistered.complete(Unit)
                check(allowNewestCancel.await(5, TimeUnit.SECONDS))
              }
            }
            FLoader.ReplacedCancellationException()
          },
          newBusyCause = { FLoader.BusyCancellationException() },
        )

        val first = async {
          runCatching { mutator.mutate { awaitCancellation() } }.exceptionOrNull()
        }.also { runCurrent() }

        // 使用独立调度器，让排队任务在旧任务结束后停在恢复前，此时有已登记的任务但没有运行任务
        val queued = async(queuedDispatcher + queuedScheduler) {
          runCatching { mutator.mutate { queuedEntered.set(true) } }.exceptionOrNull()
        }
        try {
          queuedScheduler.runCurrent()
          runCurrent()
          assertTrue(first.await() is FLoader.ReplacedCancellationException)
          assertEquals(false, queued.isCompleted)

          val older = async(olderDispatcher) {
            runCatching { mutator.mutate { olderEntered.set(true) } }.exceptionOrNull()
          }
          olderRegistered.await()
          val newest = async(newestDispatcher) { mutator.mutate { 3 } }
          newestRegistered.await()

          // 最新任务尚未发起取消，较旧任务没有可等待的运行任务，仍须自行识别替换并退出
          allowOlderCancel.countDown()
          assertTrue(older.await() is FLoader.ReplacedCancellationException)
          assertEquals(false, olderEntered.get())
          assertEquals(false, newest.isCompleted)

          allowNewestCancel.countDown()
          assertEquals(3, newest.await())
          queuedScheduler.runCurrent()
          assertTrue(queued.await() is FLoader.ReplacedCancellationException)
          assertEquals(false, queuedEntered.get())
          assertEquals(4, mutator.mutateOrThrow { 4 })
        } finally {
          allowOlderCancel.countDown()
          allowNewestCancel.countDown()
          queuedScheduler.runCurrent()
          runCurrent()
        }
      }
    }
  }

  @Test(timeout = 10_000)
  fun `test replaced queued task is not registered as running task`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { queuedDispatcher ->
      Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { latestDispatcher ->
        val replacements = AtomicInteger()
        val latestRegistered = CompletableDeferred<Unit>()
        val allowLatestCancel = CountDownLatch(1)
        val queuedReplaced = CompletableDeferred<Unit>()
        val allowQueuedCancel = CountDownLatch(1)
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val queuedEntered = AtomicBoolean()
        val latestEntered = AtomicBoolean()
        val mutator = FMutator(
          newCancelCause = { FLoader.ManualCancellationException() },
          newReplaceCause = {
            when (replacements.incrementAndGet()) {
              2 -> {
                // 最新任务已登记，暂停它对排队任务的取消
                latestRegistered.complete(Unit)
                check(allowLatestCancel.await(5, TimeUnit.SECONDS))
              }
              3 -> {
                // 排队任务已发现自己被顶替，暂停它对自己的取消
                queuedReplaced.complete(Unit)
                check(allowQueuedCancel.await(5, TimeUnit.SECONDS))
              }
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
          val queued = async(queuedDispatcher) {
            runCatching { mutator.mutate { queuedEntered.set(true) } }.exceptionOrNull()
          }
          cleanupStarted.await()
          val latest = async(latestDispatcher) {
            runCatching { mutator.mutate { latestEntered.set(true) } }.exceptionOrNull()
          }
          latestRegistered.await()

          releaseCleanup.complete(Unit)
          queuedReplaced.await()
          assertTrue(first.await() is FLoader.ReplacedCancellationException)

          val cancelJob = launch { mutator.cancelAndJoin() }.also { runCurrent() }
          assertEquals(false, cancelJob.isCompleted)

          // 被顶替的排队任务不是运行任务，cancelAndJoin 取消不到它，它仍以替换原因退出
          allowQueuedCancel.countDown()
          assertTrue(queued.await() is FLoader.ReplacedCancellationException)
          assertEquals(false, queuedEntered.get())

          allowLatestCancel.countDown()
          assertTrue(latest.await() is FLoader.ManualCancellationException)
          assertEquals(false, latestEntered.get())
          cancelJob.join()
          assertEquals(4, mutator.mutateOrThrow { 4 })
        } finally {
          allowLatestCancel.countDown()
          allowQueuedCancel.countDown()
          releaseCleanup.complete(Unit)
        }
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
        newBusyCause = { FLoader.BusyCancellationException() },
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

  @Test(timeout = 10_000)
  fun `test cancelAndJoin cancels queued task before running task`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { queuedDispatcher ->
      val cancelCauses = AtomicInteger()
      val queuedRegistered = CompletableDeferred<Unit>()
      val allowQueuedCancel = CountDownLatch(1)
      val queuedFinished = CountDownLatch(1)
      val queuedEntered = AtomicBoolean()
      val mutator = FMutator(
        newCancelCause = {
          if (cancelCauses.incrementAndGet() == 2) {
            // 第一个任务已取消、第二个尚未取消时，放行排队任务让它尝试执行
            allowQueuedCancel.countDown()
            check(queuedFinished.await(5, TimeUnit.SECONDS))
          }
          FLoader.ManualCancellationException()
        },
        newReplaceCause = {
          queuedRegistered.complete(Unit)
          check(allowQueuedCancel.await(5, TimeUnit.SECONDS))
          FLoader.ReplacedCancellationException()
        },
        newBusyCause = { FLoader.BusyCancellationException() },
      )

      // 运行中任务在 Unconfined 上，取消时会在取消方线程内联结束
      val first = async(Dispatchers.Unconfined) {
        runCatching { mutator.mutate { awaitCancellation() } }.exceptionOrNull()
      }
      val queued = async(queuedDispatcher) {
        runCatching { mutator.mutate { queuedEntered.set(true) } }.exceptionOrNull()
      }
      queued.invokeOnCompletion { queuedFinished.countDown() }

      try {
        queuedRegistered.await()
        mutator.cancelAndJoin()
        // 先取消排队任务再取消运行任务，排队任务才不会趁运行任务内联结束时开始执行
        assertEquals(false, queuedEntered.get())
        assertTrue(queued.await() is FLoader.ManualCancellationException)
        assertTrue(first.await() is FLoader.ReplacedCancellationException)
        assertEquals(3, mutator.mutateOrThrow { 3 })
      } finally {
        allowQueuedCancel.countDown()
        queuedFinished.countDown()
        first.cancelAndJoin()
        queued.cancelAndJoin()
      }
    }
  }

  // 任务已登记为运行任务但尚未进入回调时被取消或替换，load 与 tryLoad 的登记路径不同，需分别覆盖
  private suspend fun TestScope.checkRegisteredTaskCancellation(useTryLoad: Boolean, replace: Boolean) {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { callerDispatcher ->
      val loader = FLoader()
      val callerPaused = CompletableDeferred<Unit>()
      val releaseCaller = CountDownLatch(1)
      val probePaused = AtomicBoolean()
      val callbackEntered = AtomicBoolean()
      val replacementEntered = AtomicBoolean()
      val contextProbe = PauseOnRepeatedContextKey {
        probePaused.set(true)
        callerPaused.complete(Unit)
        check(releaseCaller.await(5, TimeUnit.SECONDS))
      }
      val caller = async(callerDispatcher + contextProbe) {
        contextProbe.arm()
        try {
          runCatching {
            if (useTryLoad) loader.tryLoad { callbackEntered.set(true) } else loader.load { callbackEntered.set(true) }
          }.exceptionOrNull()
        } finally {
          callerPaused.complete(Unit)
        }
      }

      try {
        callerPaused.await()
        assertEquals(true, probePaused.get())
        assertEquals(false, callbackEntered.get())
        assertEquals(false, loader.isLoading())

        val next = async {
          if (replace) {
            loader.load {
              replacementEntered.set(true)
              2
            }.getOrThrow()
          } else {
            loader.cancelAndJoin()
            2
          }
        }.also { runCurrent() }

        assertEquals(false, caller.isCompleted)
        assertEquals(false, next.isCompleted)
        assertEquals(false, callbackEntered.get())
        assertEquals(false, replacementEntered.get())
        assertTrue(runCatching { loader.tryLoad { 3 } }.exceptionOrNull() is FLoader.BusyCancellationException)

        releaseCaller.countDown()
        val cause = caller.await()
        assertTrue(if (replace) cause is FLoader.ReplacedCancellationException else cause is FLoader.ManualCancellationException)
        assertEquals(2, next.await())
        assertEquals(false, callbackEntered.get())
        assertEquals(replace, replacementEntered.get())
        assertEquals(false, loader.isLoading())
        assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
      } finally {
        releaseCaller.countDown()
        caller.cancelAndJoin()
      }
    }
  }

  private class PauseOnRepeatedContextKey(private val onRepeatedKey: () -> Unit) : AbstractCoroutineContextElement(Key) {
    private var _armed = false
    private var _firstKey: CoroutineContext.Key<*>? = null

    fun arm() {
      _armed = true
    }

    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
      if (_armed) {
        // 首次查询来自入口嵌套检查，再次查询同一 key 时任务已登记
        if (_firstKey == null) {
          _firstKey = key
        } else if (key === _firstKey) {
          _armed = false
          onRepeatedKey()
        }
      }
      return super.get(key)
    }

    companion object Key : CoroutineContext.Key<PauseOnRepeatedContextKey>
  }
}
