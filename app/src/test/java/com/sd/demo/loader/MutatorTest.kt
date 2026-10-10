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
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
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
import kotlin.time.Duration.Companion.seconds

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
      assertEquals(false, loader.isBusyFlow.value)
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
      assertEquals(false, loader.isBusyFlow.value)
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

  @Test(timeout = 10_000)
  fun `test task completion clears registration inside lock`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { taskDispatcher ->
      Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { lockDispatcher ->
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CountDownLatch(1)
        val taskThread = CompletableDeferred<Thread>()
        val finishTask = CompletableDeferred<Unit>()
        val mutator = FMutator(
          newCancelCause = { FLoader.ManualCancellationException() },
          newReplaceCause = { FLoader.ReplacedCancellationException() },
          newBusyCause = {
            // 忙异常在锁内创建，借它从外部持有锁
            lockHeld.complete(Unit)
            check(releaseLock.await(5, TimeUnit.SECONDS))
            FLoader.BusyCancellationException()
          },
        )

        val task = async(taskDispatcher) {
          mutator.mutate {
            taskThread.complete(Thread.currentThread())
            finishTask.await()
            1
          }
        }
        try {
          val thread = taskThread.await()
          val busy = async(lockDispatcher) {
            runCatching { mutator.mutateOrThrow { 2 } }.exceptionOrNull()
          }
          lockHeld.await()

          // 持锁期间让任务结束，它的清空必须被锁挡住
          finishTask.complete(Unit)
          assertEquals(true, awaitBlocked(thread) { task.isCompleted })
          assertEquals(false, task.isCompleted)

          releaseLock.countDown()
          assertTrue(busy.await() is FLoader.BusyCancellationException)
          assertEquals(1, task.await())
          assertEquals(3, mutator.mutateOrThrow { 3 })
        } finally {
          finishTask.complete(Unit)
          releaseLock.countDown()
        }
      }
    }
  }

  @Test(timeout = 10_000)
  fun `test cancelAndJoin reads registration inside lock`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { cancelDispatcher ->
      Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { lockDispatcher ->
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CountDownLatch(1)
        val cancelThread = CompletableDeferred<Thread>()
        val startCancel = CompletableDeferred<Unit>()
        val cancelCauseCreated = AtomicBoolean()
        val mutator = FMutator(
          newCancelCause = {
            cancelCauseCreated.set(true)
            FLoader.ManualCancellationException()
          },
          newReplaceCause = { FLoader.ReplacedCancellationException() },
          newBusyCause = {
            // 忙异常在锁内创建，借它从外部持有锁
            lockHeld.complete(Unit)
            check(releaseLock.await(5, TimeUnit.SECONDS))
            FLoader.BusyCancellationException()
          },
        )

        val first = async {
          runCatching { mutator.mutate { awaitCancellation() } }.exceptionOrNull()
        }.also { runCurrent() }
        val cancelJob = launch(cancelDispatcher) {
          cancelThread.complete(Thread.currentThread())
          startCancel.await()
          mutator.cancelAndJoin()
        }
        try {
          val thread = cancelThread.await()
          val busy = async(lockDispatcher) {
            runCatching { mutator.mutateOrThrow { 2 } }.exceptionOrNull()
          }
          lockHeld.await()

          // 持锁期间发起 cancelAndJoin，它必须在读取登记时被锁挡住，此时还没有发起取消
          startCancel.complete(Unit)
          assertEquals(true, awaitBlocked(thread) { cancelCauseCreated.get() })
          assertEquals(false, cancelCauseCreated.get())

          releaseLock.countDown()
          assertTrue(busy.await() is FLoader.BusyCancellationException)
          cancelJob.join()
          assertTrue(first.await() is FLoader.ManualCancellationException)
          assertEquals(3, mutator.mutateOrThrow { 3 })
        } finally {
          startCancel.complete(Unit)
          releaseLock.countDown()
        }
      }
    }
  }

  @Test
  fun `test isBusy follows task lifecycle`() = runTest {
    val mutator = newMutator()
    val releaseCleanup = CompletableDeferred<Unit>()
    var busyInBlock: Boolean? = null
    assertEquals(false, mutator.isBusy())

    val task = async {
      runCatching {
        mutator.mutate {
          busyInBlock = mutator.isBusy()
          try {
            awaitCancellation()
          } finally {
            withContext(NonCancellable) { releaseCleanup.await() }
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      assertEquals(true, busyInBlock)
      assertEquals(true, mutator.isBusy())

      // 任务已被取消但仍在清理，仍算忙
      val cancelJob = launch { mutator.cancelAndJoin() }.also { runCurrent() }
      assertEquals(false, task.isCompleted)
      assertEquals(true, mutator.isBusy())

      releaseCleanup.complete(Unit)
      cancelJob.join()
      assertTrue(task.await() is FLoader.ManualCancellationException)
      assertEquals(false, mutator.isBusy())

      assertEquals(true, mutator.mutateOrThrow { mutator.isBusy() })
      assertEquals(false, mutator.isBusy())
    } finally {
      releaseCleanup.complete(Unit)
    }
  }

  @Test(timeout = 10_000)
  fun `test isBusy when queued task not resumed after previous finished`() = runTest {
    val mutator = newMutator()
    val queuedScheduler = TestCoroutineScheduler()
    val queuedDispatcher = StandardTestDispatcher(queuedScheduler)
    val first = async {
      runCatching { mutator.mutate { awaitCancellation() } }.exceptionOrNull()
    }.also { runCurrent() }

    // 使用独立调度器，让排队任务在旧任务结束后停在恢复前
    val queued = async(queuedDispatcher + queuedScheduler) { mutator.mutate { 2 } }
    try {
      queuedScheduler.runCurrent()
      runCurrent()
      assertTrue(first.await() is FLoader.ReplacedCancellationException)
      assertEquals(false, queued.isCompleted)

      // 没有运行任务，但排队任务尚未结束，仍算忙
      assertEquals(true, mutator.isBusy())

      queuedScheduler.runCurrent()
      assertEquals(2, queued.await())
      assertEquals(false, mutator.isBusy())
    } finally {
      queuedScheduler.runCurrent()
      runCurrent()
    }
  }

  @Test
  fun `test isBusy when queued task cancelled during previous cleanup`() = runTest {
    val mutator = newMutator()
    val releaseCleanup = CompletableDeferred<Unit>()
    val first = async {
      runCatching {
        mutator.mutate {
          try {
            awaitCancellation()
          } finally {
            withContext(NonCancellable) { releaseCleanup.await() }
          }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      val queued = launch { mutator.mutate { } }.also { runCurrent() }
      queued.cancelAndJoin()
      assertEquals(false, first.isCompleted)

      // 排队任务已结束，旧任务仍在清理，仍算忙
      assertEquals(true, mutator.isBusy())

      releaseCleanup.complete(Unit)
      assertTrue(first.await() is FLoader.ReplacedCancellationException)
      assertEquals(false, mutator.isBusy())
    } finally {
      releaseCleanup.complete(Unit)
    }
  }

  @Test(timeout = 10_000)
  fun `test isBusy is false after task completed before registration cleared`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { taskDispatcher ->
      Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { lockDispatcher ->
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CountDownLatch(1)
        val taskThread = CompletableDeferred<Thread>()
        val finishTask = CompletableDeferred<Unit>()
        val busyBeforeCleared = AtomicBoolean(true)
        lateinit var mutator: FMutator
        mutator = FMutator(
          newCancelCause = { FLoader.ManualCancellationException() },
          newReplaceCause = { FLoader.ReplacedCancellationException() },
          newBusyCause = {
            // 忙异常在锁内创建，借它从外部持有锁
            lockHeld.complete(Unit)
            check(releaseLock.await(5, TimeUnit.SECONDS))
            // 任务已完成而清空还被锁挡着，登记仍指向它；锁可重入，在持锁线程上读到的就是这个窗口
            busyBeforeCleared.set(mutator.isBusy())
            FLoader.BusyCancellationException()
          },
        )

        val task = async(taskDispatcher) {
          mutator.mutate {
            taskThread.complete(Thread.currentThread())
            finishTask.await()
            1
          }
        }
        try {
          val thread = taskThread.await()
          val busy = async(lockDispatcher) {
            runCatching { mutator.mutateOrThrow { 2 } }.exceptionOrNull()
          }
          lockHeld.await()

          // 持锁期间让任务结束，它的清空被锁挡住
          finishTask.complete(Unit)
          assertEquals(true, awaitBlocked(thread) { task.isCompleted })

          // 判忙看任务是否完成，不看字段是否为 null
          releaseLock.countDown()
          assertTrue(busy.await() is FLoader.BusyCancellationException)
          assertEquals(false, busyBeforeCleared.get())
          assertEquals(1, task.await())
          assertEquals(false, mutator.isBusy())
        } finally {
          finishTask.complete(Unit)
          releaseLock.countDown()
        }
      }
    }
  }

  @Test(timeout = 10_000)
  fun `test isBusy reads registration inside lock`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { queryDispatcher ->
      Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { lockDispatcher ->
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CountDownLatch(1)
        val queryThread = CompletableDeferred<Thread>()
        val startQuery = CompletableDeferred<Unit>()
        val queryReturned = AtomicBoolean()
        val mutator = FMutator(
          newCancelCause = { FLoader.ManualCancellationException() },
          newReplaceCause = { FLoader.ReplacedCancellationException() },
          newBusyCause = {
            // 忙异常在锁内创建，借它从外部持有锁
            lockHeld.complete(Unit)
            check(releaseLock.await(5, TimeUnit.SECONDS))
            FLoader.BusyCancellationException()
          },
        )

        val first = async {
          runCatching { mutator.mutate { awaitCancellation() } }.exceptionOrNull()
        }.also { runCurrent() }
        val query = async(queryDispatcher) {
          queryThread.complete(Thread.currentThread())
          startQuery.await()
          mutator.isBusy().also { queryReturned.set(true) }
        }
        try {
          val thread = queryThread.await()
          val busy = async(lockDispatcher) {
            runCatching { mutator.mutateOrThrow { 2 } }.exceptionOrNull()
          }
          lockHeld.await()

          // 持锁期间调用 isBusy，它必须在读取登记时被锁挡住
          startQuery.complete(Unit)
          assertEquals(true, awaitBlocked(thread) { queryReturned.get() })
          assertEquals(false, queryReturned.get())

          releaseLock.countDown()
          assertTrue(busy.await() is FLoader.BusyCancellationException)
          assertEquals(true, query.await())

          mutator.cancelAndJoin()
          assertTrue(first.await() is FLoader.ManualCancellationException)
          assertEquals(false, mutator.isBusy())
        } finally {
          startQuery.complete(Unit)
          releaseLock.countDown()
        }
      }
    }
  }

  @Test
  fun `test isBusyFlow follows task lifecycle`() = runTest {
    val mutator = newMutator()
    val values = mutableListOf<Boolean>()
    val collector = launch(Dispatchers.Unconfined) { mutator.isBusyFlow.collect { values.add(it) } }
    val releaseCleanup = CompletableDeferred<Unit>()

    try {
      // 登记后、进入 block 前已同步为 true，任务结束后同步为 false
      assertEquals(false, mutator.isBusyFlow.value)
      assertEquals(true, mutator.mutate { mutator.isBusyFlow.value })
      assertEquals(false, mutator.isBusyFlow.value)
      assertEquals(true, mutator.mutateOrThrow { mutator.isBusyFlow.value })
      assertEquals(false, mutator.isBusyFlow.value)
      assertEquals(listOf(false, true, false, true, false), values)

      val first = async {
        runCatching {
          mutator.mutate {
            try {
              awaitCancellation()
            } finally {
              withContext(NonCancellable) { releaseCleanup.await() }
            }
          }
        }.exceptionOrNull()
      }.also { runCurrent() }
      val queued = async { mutator.mutate { 2 } }.also { runCurrent() }
      assertEquals(false, first.isCompleted)
      assertEquals(true, mutator.isBusyFlow.value)

      // 判忙的调用不改变状态
      assertTrue(runCatching { mutator.mutateOrThrow { 3 } }.exceptionOrNull() is FLoader.BusyCancellationException)
      assertEquals(true, mutator.isBusyFlow.value)

      releaseCleanup.complete(Unit)
      assertEquals(2, queued.await())
      assertTrue(first.await() is FLoader.ReplacedCancellationException)
      assertEquals(false, mutator.isBusyFlow.value)

      // 旧任务清理、排队任务等待和接替执行期间一直为 true，没有多余的翻转
      assertEquals(listOf(false, true, false, true, false, true, false), values)
    } finally {
      releaseCleanup.complete(Unit)
      collector.cancelAndJoin()
    }
  }

  @Test(timeout = 10_000)
  fun `test isBusyFlow is updated outside lock for load`() = runTest {
    checkBusyFlowUpdatedOutsideLock(useTryLoad = false)
  }

  @Test(timeout = 10_000)
  fun `test isBusyFlow is updated outside lock for tryLoad`() = runTest {
    checkBusyFlowUpdatedOutsideLock(useTryLoad = true)
  }

  // 旧任务结束与新任务登记并发，两边的同步都返回后状态流必须与登记一致
  @Test(timeout = 20_000)
  fun `test isBusyFlow is true after concurrent completion and registration on multiple threads`() = runTest(timeout = 20.seconds) {
    withContext(Dispatchers.Default) {
      repeat(3000) {
        val mutator = newMutator()
        val firstEntered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        coroutineScope {
          val first = launch {
            runCatching {
              mutator.mutate {
                firstEntered.complete(Unit)
                finishFirst.await()
              }
            }
          }
          firstEntered.await()

          // 旧任务结束与新任务登记并发
          val second = launch {
            mutator.mutate {
              secondEntered.complete(Unit)
              releaseSecond.await()
            }
          }
          finishFirst.complete(Unit)
          try {
            first.join()
            secondEntered.await()
            // 新任务仍在运行
            assertEquals(true, mutator.isBusyFlow.value)
          } finally {
            releaseSecond.complete(Unit)
          }
          second.join()
        }
        assertEquals(false, mutator.isBusyFlow.value)
      }
    }
  }

  // 收集者在状态流的赋值调用中内联执行；赋值在锁内的话，收集者卡住期间其他线程读取登记会被挡住
  private suspend fun TestScope.checkBusyFlowUpdatedOutsideLock(useTryLoad: Boolean) {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { taskDispatcher ->
      Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { queryDispatcher ->
        val mutator = newMutator()
        val collectorEntered = List(2) { CompletableDeferred<Unit>() }
        val releaseCollector = List(2) { CountDownLatch(1) }
        // 卡住变为 true 和恢复 false 这两次内联执行
        val collector = launch(Dispatchers.Unconfined) {
          mutator.isBusyFlow.drop(1).take(2).collectIndexed { index, _ ->
            collectorEntered[index].complete(Unit)
            check(releaseCollector[index].await(5, TimeUnit.SECONDS))
          }
        }
        val task = async(taskDispatcher) {
          if (useTryLoad) mutator.mutateOrThrow { 1 } else mutator.mutate { 1 }
        }

        try {
          repeat(2) { index ->
            collectorEntered[index].await()
            val queryThread = CompletableDeferred<Thread>()
            val queryReturned = AtomicBoolean()
            val query = async(queryDispatcher) {
              queryThread.complete(Thread.currentThread())
              mutator.isBusy().also { queryReturned.set(true) }
            }
            assertEquals(false, awaitBlocked(queryThread.await()) { queryReturned.get() })
            assertEquals(true, queryReturned.get())
            assertEquals(index == 0, query.await())
            releaseCollector[index].countDown()
          }
          assertEquals(1, task.await())
          collector.join()
          assertEquals(false, mutator.isBusyFlow.value)
        } finally {
          releaseCollector.forEach { it.countDown() }
        }
      }
    }
  }

  private fun newMutator() = FMutator(
    newCancelCause = { FLoader.ManualCancellationException() },
    newReplaceCause = { FLoader.ReplacedCancellationException() },
    newBusyCause = { FLoader.BusyCancellationException() },
  )

  // 等目标线程被锁挡住；目标操作越过了锁或超时则返回 false
  private fun awaitBlocked(thread: Thread, passed: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
      if (passed()) return false
      if (thread.state == Thread.State.BLOCKED) return true
      Thread.sleep(1)
    }
    return false
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
        assertEquals(true, loader.isBusyFlow.value)

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
        assertEquals(false, loader.isBusyFlow.value)
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
