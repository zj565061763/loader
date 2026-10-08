package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FMutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class MutexTest {

  private class BusinessException(val code: Int) : RuntimeException("business error: $code")

  @Test
  fun `test withLock`() = runTest {
    val mutex = FMutex()
    val result = mutex.withLock { 1 }
    assertEquals(1, result)
  }

  @Test
  fun `test withLock when error in block`() = runTest {
    val mutex = FMutex()
    runCatching {
      mutex.withLock { error("error in block") }
    }.also { result ->
      assertEquals("error in block", result.exceptionOrNull()!!.message)
    }
    // 锁应已释放，可再次获取
    assertEquals(2, mutex.withLock { 2 })
  }

  @Test
  fun `test emit inside withLock fails and releases lock`() = runTest {
    val mutex = FMutex()
    flow<Int> {
      mutex.withLock { emit(1) }
    }.test {
      assertEquals(true, awaitError() is IllegalStateException)
    }
    assertEquals(2, mutex.withLock { 2 })
  }

  @Test
  fun `test send inside withLock with channelFlow`() = runTest {
    val mutex = FMutex()
    channelFlow {
      mutex.withLock { send(1) }
    }.test {
      assertEquals(1, awaitItem())
      awaitComplete()
    }
    assertEquals(2, mutex.withLock { 2 })
  }

  @Test
  fun `test emit outside withLock with flow`() = runTest {
    val mutex = FMutex()
    flow {
      emit(mutex.withLock { 1 })
    }.test {
      assertEquals(1, awaitItem())
      awaitComplete()
    }
    assertEquals(2, mutex.withLock { 2 })
  }

  @Test
  fun `test withLock mutually exclusive`() = runTest {
    val mutex = FMutex()
    val container = mutableListOf<String>()

    launch {
      mutex.withLock {
        container.add("1-start")
        delay(1_000)
        container.add("1-end")
      }
    }.also { runCurrent() }

    launch {
      mutex.withLock {
        container.add("2-start")
        container.add("2-end")
      }
    }.also { runCurrent() }

    // 第二个协程必须等第一个释放锁后才能进入
    assertEquals(listOf("1-start"), container)
    advanceUntilIdle()
    assertEquals(listOf("1-start", "1-end", "2-start", "2-end"), container)
  }

  @Test
  fun `test withLock waits for child success before releasing lock`() = runTest {
    val mutex = FMutex()
    val releaseChild = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    val holderJob = async {
      mutex.withLock {
        CoroutineScope(currentCoroutineContext()).launch {
          releaseChild.await()
          container.add("child-finished")
        }
        container.add("callback-returned")
        1
      }
    }.also { runCurrent() }

    try {
      assertEquals(listOf("callback-returned"), container)
      assertEquals(false, holderJob.isCompleted)
      val waiterJob = async {
        mutex.withLock {
          container.add("waiter")
          2
        }
      }.also { runCurrent() }

      assertEquals(false, waiterJob.isCompleted)
      assertEquals(listOf("callback-returned"), container)

      releaseChild.complete(Unit)
      assertEquals(1, holderJob.await())
      assertEquals(2, waiterJob.await())
      assertEquals(listOf("callback-returned", "child-finished", "waiter"), container)
    } finally {
      releaseChild.complete(Unit)
    }
    assertEquals(3, mutex.withLock { 3 })
  }

  @Test
  fun `test withLock child failure waits for sibling cleanup before releasing lock`() = runTest {
    val mutex = FMutex()
    val cause = BusinessException(2)
    val failChild = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    val holderJob = async {
      runCatching {
        mutex.withLock {
          val scope = CoroutineScope(currentCoroutineContext())
          scope.launch {
            try {
              delay(Long.MAX_VALUE)
            } finally {
              withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
              }
              container.add("sibling-cleaned")
            }
          }
          scope.launch {
            failChild.await()
            throw cause
          }
          container.add("callback-returned")
          1
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      assertEquals(false, holderJob.isCompleted)
      assertEquals(listOf("callback-returned"), container)
      val waiterJob = async {
        mutex.withLock {
          container.add("waiter")
          2
        }
      }.also { runCurrent() }
      assertEquals(false, waiterJob.isCompleted)

      failChild.complete(Unit)
      runCurrent()
      assertEquals(true, cleanupStarted.isCompleted)
      assertEquals(false, holderJob.isCompleted)
      assertEquals(false, waiterJob.isCompleted)
      assertEquals(listOf("callback-returned"), container)

      releaseCleanup.complete(Unit)
      assertSame(cause, holderJob.await())
      assertEquals(2, waiterJob.await())
      assertEquals(listOf("callback-returned", "sibling-cleaned", "waiter"), container)
    } finally {
      failChild.complete(Unit)
      releaseCleanup.complete(Unit)
    }
    assertEquals(3, mutex.withLock { 3 })
  }

  @Test
  fun `test withLock waits for child cancellation cleanup before releasing lock`() = runTest {
    val mutex = FMutex()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    val holderJob = launch {
      mutex.withLock {
        CoroutineScope(currentCoroutineContext()).launch {
          try {
            delay(Long.MAX_VALUE)
          } finally {
            withContext(NonCancellable) {
              cleanupStarted.complete(Unit)
              releaseCleanup.await()
            }
            container.add("child-cleaned")
          }
        }
        container.add("callback-returned")
      }
    }.also { runCurrent() }

    try {
      assertEquals(listOf("callback-returned"), container)
      holderJob.cancel()
      runCurrent()
      assertEquals(true, cleanupStarted.isCompleted)
      assertEquals(false, holderJob.isCompleted)
      val waiterJob = async {
        mutex.withLock {
          container.add("waiter")
          2
        }
      }.also { runCurrent() }

      assertEquals(false, waiterJob.isCompleted)
      assertEquals(listOf("callback-returned"), container)

      releaseCleanup.complete(Unit)
      holderJob.join()
      assertEquals(true, holderJob.isCancelled)
      assertEquals(2, waiterJob.await())
      assertEquals(listOf("callback-returned", "child-cleaned", "waiter"), container)
    } finally {
      releaseCleanup.complete(Unit)
      holderJob.cancelAndJoin()
    }
    assertEquals(3, mutex.withLock { 3 })
  }

  @Test
  fun `test withLock releases when cancel`() = runTest {
    val mutex = FMutex()
    val job = launch {
      mutex.withLock { delay(Long.MAX_VALUE) }
    }.also { runCurrent() }

    job.cancel()
    runCurrent()

    // 取消后锁应释放
    assertEquals(1, mutex.withLock { 1 })
  }

  @Test
  fun `test withLock when cancel waiter`() = runTest {
    val mutex = FMutex()
    val release = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()

    val holderJob = launch {
      mutex.withLock {
        container.add("holder")
        release.await()
      }
    }.also { runCurrent() }

    try {
      val waiterJob = launch {
        mutex.withLock { container.add("waiter") }
      }.also { runCurrent() }

      // 持锁者未释放时取消等待者，等待者立即结束且回调不执行
      waiterJob.cancel()
      runCurrent()
      assertEquals(true, waiterJob.isCompleted)
      assertEquals(false, holderJob.isCompleted)
      assertEquals(listOf("holder"), container)
    } finally {
      release.complete(Unit)
    }
    advanceUntilIdle()
    assertEquals(listOf("holder"), container)

    // 等待者取消不影响锁，后续仍能获取
    assertEquals(1, mutex.withLock { 1 })
  }

  @Test
  fun `test withLock nested same instance`() = runTest {
    val mutex = FMutex()
    val exception = mutex.withLock {
      runCatching {
        mutex.withLock { }
      }.exceptionOrNull()
    }
    assertEquals(true, exception is IllegalStateException)
    assertFalse(exception is CancellationException)
    assertEquals("Nested invoke", exception?.message)
  }

  @Test
  fun `test withLock nested different instances`() = runTest {
    val mutexA = FMutex()
    val mutexB = FMutex()
    val result = mutexA.withLock {
      mutexB.withLock { 42 }
    }
    assertEquals(42, result)
  }

  @Test(timeout = 10_000)
  fun `test withLock mutually exclusive on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      val mutex = FMutex()
      val start = CompletableDeferred<Unit>()
      val running = AtomicInteger()
      val overlapped = AtomicBoolean()
      var completed = 0

      coroutineScope {
        List(8) {
          launch {
            start.await()
            repeat(100) {
              mutex.withLock {
                if (running.incrementAndGet() != 1) overlapped.set(true)
                try {
                  val previous = completed
                  yield()
                  completed = previous + 1
                } finally {
                  running.decrementAndGet()
                }
              }
            }
          }
        }.also { jobs ->
          start.complete(Unit)
          jobs.joinAll()
        }
      }

      assertEquals(false, overlapped.get())
      assertEquals(0, running.get())
      assertEquals(800, completed)
      assertEquals(1, mutex.withLock { 1 })
    }
  }

  @Test(timeout = 10_000)
  fun `test withLock releases after error on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      val mutex = FMutex()
      val cause = BusinessException(1)
      val started = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      val holderJob = async {
        runCatching {
          mutex.withLock {
            started.complete(Unit)
            release.await()
            throw cause
          }
        }.exceptionOrNull()
      }

      try {
        started.await()
        val waiterJob = async(start = CoroutineStart.UNDISPATCHED) {
          mutex.withLock { 2 }
        }
        assertEquals(false, waiterJob.isCompleted)
        release.complete(Unit)

        assertSame(cause, holderJob.await())
        assertEquals(2, waiterJob.await())
        assertEquals(3, mutex.withLock { 3 })
      } finally {
        release.complete(Unit)
      }
    }
  }

  @Test(timeout = 10_000)
  fun `test withLock releases after cancellation on multiple threads`() = runTest {
    withContext(Dispatchers.Default) {
      repeat(50) {
        val mutex = FMutex()
        val started = CompletableDeferred<Unit>()
        val cancelledWaiterEntered = AtomicBoolean()
        val holderJob = launch {
          mutex.withLock {
            started.complete(Unit)
            delay(Long.MAX_VALUE)
          }
        }

        try {
          started.await()
          val cancelledWaiterJob = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock { cancelledWaiterEntered.set(true) }
          }
          assertEquals(false, cancelledWaiterJob.isCompleted)
          cancelledWaiterJob.cancelAndJoin()
          assertEquals(false, cancelledWaiterEntered.get())
          assertEquals(false, holderJob.isCompleted)

          val waiterJob = async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock { 2 }
          }
          assertEquals(false, waiterJob.isCompleted)
          holderJob.cancelAndJoin()

          assertEquals(2, waiterJob.await())
          assertEquals(3, mutex.withLock { 3 })
        } finally {
          holderJob.cancelAndJoin()
        }
      }
    }
  }

  @Test(timeout = 10_000)
  fun `test withLock nested across dispatchers`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { firstDispatcher ->
      Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { secondDispatcher ->
        val mutex = FMutex()
        val otherMutex = FMutex()

        withContext(firstDispatcher) {
          mutex.withLock {
            withContext(secondDispatcher) {
              runCatching {
                // 回归时限时退出，释放外层锁并关闭专用线程
                withTimeout(5_000) { mutex.withLock { } }
              }.also { result ->
                assertEquals(true, result.exceptionOrNull() is IllegalStateException)
                assertFalse(result.exceptionOrNull() is CancellationException)
                assertEquals("Nested invoke", result.exceptionOrNull()!!.message)
              }
              assertEquals(1, otherMutex.withLock { 1 })
            }
          }
        }

        assertEquals(2, mutex.withLock { 2 })
        assertEquals(3, otherMutex.withLock { 3 })
      }
    }
  }
}
