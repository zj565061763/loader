package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FMutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

  private class CustomCancellationException(val code: Int) : CancellationException("caller cancelled: $code")

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
  fun `test withLock action failure cancels child and waits for cleanup before releasing lock`() = runTest {
    val mutex = FMutex()
    val cause = BusinessException(3)
    val childStarted = CompletableDeferred<Unit>()
    val failAction = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    val holderJob = async {
      runCatching {
        mutex.withLock {
          CoroutineScope(currentCoroutineContext()).launch {
            try {
              childStarted.complete(Unit)
              delay(Long.MAX_VALUE)
            } finally {
              withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
              }
              container.add("child-cleaned")
            }
          }
          childStarted.await()
          failAction.await()
          throw cause
        }
      }.exceptionOrNull()
    }.also { runCurrent() }
    val waiterJob = async {
      mutex.withLock {
        container.add("waiter")
        2
      }
    }.also { runCurrent() }

    try {
      assertEquals(true, childStarted.isCompleted)
      assertEquals(false, holderJob.isCompleted)
      assertEquals(false, waiterJob.isCompleted)

      failAction.complete(Unit)
      runCurrent()
      assertEquals(true, cleanupStarted.isCompleted)
      assertEquals(false, holderJob.isCompleted)
      assertEquals(false, waiterJob.isCompleted)
      assertEquals(emptyList<String>(), container)

      releaseCleanup.complete(Unit)
      assertSame(cause, holderJob.await())
      assertEquals(2, waiterJob.await())
      assertEquals(listOf("child-cleaned", "waiter"), container)
    } finally {
      failAction.complete(Unit)
      releaseCleanup.complete(Unit)
      holderJob.cancelAndJoin()
      waiterJob.cancelAndJoin()
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
  fun `test withLock when action throws CancellationException`() = runTest {
    val mutex = FMutex()
    val cause = CustomCancellationException(3)
    val thrown = runCatching { mutex.withLock { throw cause } }.exceptionOrNull()

    // 取消异常原样抛出，不取消调用方，锁也已释放
    assertSame(cause, thrown)
    assertEquals(true, currentCoroutineContext().isActive)
    assertEquals(2, mutex.withLock { 2 })
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

  @Test(timeout = 10_000)
  fun `test withLock nested in NonCancellable`() = runTest {
    val mutex = FMutex()
    // NonCancellable 不改变嵌套元素，同一实例嵌套仍会被拦截而不是自锁
    val exception = mutex.withLock {
      withContext(NonCancellable) {
        runCatching { mutex.withLock { } }.exceptionOrNull()
      }
    }
    assertEquals(true, exception is IllegalStateException)
    assertFalse(exception is CancellationException)
    assertEquals("Nested invoke", exception?.message)
    assertEquals(2, mutex.withLock { 2 })
  }

  @Test
  fun `test withLock nested in child coroutine`() = runTest {
    val mutex = FMutex()
    // 子协程继承上下文，嵌套调用同样会被检测
    val exception = runCatching {
      mutex.withLock {
        coroutineScope {
          launch { mutex.withLock { } }
        }
      }
    }.exceptionOrNull()
    assertEquals(true, exception is IllegalStateException)
    assertFalse(exception is CancellationException)
    assertEquals("Nested invoke", exception?.message)
    assertEquals(2, mutex.withLock { 2 })
  }

  @Test(timeout = 10_000)
  fun `test withLock nested in new root scope waits for lock`() = runTest {
    val mutex = FMutex()
    val container = mutableListOf<String>()
    // 独立的根 scope 不继承上下文，嵌套检测失效，内层调用会等待锁而不是抛出 Nested invoke
    val rootScope = CoroutineScope(StandardTestDispatcher(testScheduler))

    try {
      lateinit var inner: Deferred<Unit>
      val exception = mutex.withLock {
        container.add("outer-started")
        inner = rootScope.async { mutex.withLock { container.add("inner") } }
        // 持锁期间等待内层会自锁，用超时退出
        runCatching { withTimeout(1_000) { inner.await() } }.exceptionOrNull()
      }
      assertEquals(true, exception is TimeoutCancellationException)
      assertEquals(listOf("outer-started"), container)

      // 外层释放锁后内层才执行
      inner.await()
      assertEquals(listOf("outer-started", "inner"), container)
      assertEquals(2, mutex.withLock { 2 })
    } finally {
      rootScope.cancel()
    }
  }

  @Test
  fun `test withLock when caller already cancelled and mutex idle`() = runTest {
    val mutex = FMutex()
    val cause = CustomCancellationException(1)
    var entered = false
    var thrown: Throwable? = null
    val caller = launch {
      currentCoroutineContext().cancel(cause)
      thrown = runCatching { mutex.withLock { entered = true } }.exceptionOrNull()
    }.also { runCurrent() }

    assertEquals(true, caller.isCancelled)
    assertEquals(true, caller.isCompleted)
    assertSame(cause, thrown)
    assertEquals(false, entered)
    assertEquals(2, mutex.withLock { 2 })
  }

  @Test
  fun `test withLock when caller already cancelled and mutex locked`() = runTest {
    val mutex = FMutex()
    val cause = CustomCancellationException(2)
    val release = CompletableDeferred<Unit>()
    var entered = false
    var thrown: Throwable? = null
    val holder = launch { mutex.withLock { release.await() } }.also { runCurrent() }

    try {
      val caller = launch {
        currentCoroutineContext().cancel(cause)
        thrown = runCatching { mutex.withLock { entered = true } }.exceptionOrNull()
      }.also { runCurrent() }

      assertEquals(true, caller.isCancelled)
      assertEquals(true, caller.isCompleted)
      assertSame(cause, thrown)
      assertEquals(false, entered)
      assertEquals(false, holder.isCancelled)
      assertEquals(false, holder.isCompleted)
      release.complete(Unit)
      holder.join()
      assertEquals(false, holder.isCancelled)
    } finally {
      release.complete(Unit)
      holder.cancelAndJoin()
    }
    assertEquals(3, mutex.withLock { 3 })
  }

  @Test(timeout = 10_000)
  fun `test uncaught nested error releases lock for waiter`() = runTest {
    val mutex = FMutex()
    val invokeNested = CompletableDeferred<Unit>()
    val holder = async {
      runCatching {
        mutex.withLock {
          invokeNested.await()
          withTimeout(5_000) { mutex.withLock { 1 } }
        }
      }.exceptionOrNull()
    }.also { runCurrent() }
    val waiter = async { mutex.withLock { 2 } }.also { runCurrent() }

    try {
      assertEquals(false, waiter.isCompleted)
      invokeNested.complete(Unit)
      val cause = holder.await()
      assertEquals(true, cause is IllegalStateException)
      assertFalse(cause is CancellationException)
      assertEquals("Nested invoke", cause?.message)
      assertEquals(2, waiter.await())
      assertEquals(3, mutex.withLock { 3 })
    } finally {
      invokeNested.complete(Unit)
      holder.cancelAndJoin()
      waiter.cancelAndJoin()
    }
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
