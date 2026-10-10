package com.sd.demo.loader

import com.sd.lib.loader.FLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

@RunWith(Parameterized::class)
class LoaderReferenceTest(private val useTryLoad: Boolean) {

  private class PayloadException(val payload: Any) : RuntimeException("business error")

  private class PayloadCancellationException(val payload: Any) : CancellationException("custom cause")

  @Test(timeout = 10_000)
  fun `test successful load releases result`() {
    assertReleased(FLoader()) {
      loadForTest { Any() }.getOrThrow()
    }
  }

  @Test(timeout = 10_000)
  fun `test successful load releases callback capture`() {
    assertReleased(FLoader()) {
      val capture = Any()
      loadForTest { capture.hashCode() }.getOrThrow()
      capture
    }
  }

  @Test(timeout = 10_000)
  fun `test failed load releases callback capture`() {
    assertReleased(FLoader()) {
      val capture = Any()
      loadForTest<Any> { throw PayloadException(capture.hashCode()) }.exceptionOrNull()
      capture
    }
  }

  @Test(timeout = 10_000)
  fun `test cancelled load releases callback capture`() {
    assertReleased(FLoader()) {
      val capture = Any()
      runCatching { loadForTest<Any> { throw PayloadCancellationException(capture.hashCode()) } }
      capture
    }
  }

  @Test(timeout = 10_000)
  fun `test replaced load releases callback capture`() {
    checkCancelledLoadReleasesCapture(replace = true)
  }

  @Test(timeout = 10_000)
  fun `test manually cancelled load releases callback capture`() {
    checkCancelledLoadReleasesCapture(replace = false)
  }

  @Test(timeout = 10_000)
  fun `test replaced load releases callback capture while next load running`() {
    checkReplacedLoadReleasesCaptureWhileNextLoadRunning(cleanup = false)
  }

  @Test(timeout = 10_000)
  fun `test replaced load releases callback capture while next load running after previous cleanup`() {
    checkReplacedLoadReleasesCaptureWhileNextLoadRunning(cleanup = true)
  }

  @Test(timeout = 10_000)
  fun `test failed load releases exception payload`() {
    assertReleased(FLoader()) {
      val cause = loadForTest<Any> { throw PayloadException(Any()) }.exceptionOrNull() as PayloadException
      cause.payload
    }
  }

  @Test(timeout = 10_000)
  fun `test cancelled load releases cancellation payload`() {
    assertReleased(FLoader()) {
      val cause = runCatching {
        loadForTest<Any> { throw PayloadCancellationException(Any()) }
      }.exceptionOrNull() as PayloadCancellationException
      cause.payload
    }
  }

  @Test(timeout = 10_000)
  fun `test cancelled queued load releases cancellation payload`() {
    assertReleased(FLoader()) {
      coroutineScope {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
          loadForTest {
            try {
              awaitCancellation()
            } finally {
              withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
              }
            }
          }
        }

        try {
          val payload = Any()
          val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            load { error("Queued load must not run") }.getOrThrow()
          }
          cleanupStarted.await()
          assertEquals(false, first.isCompleted)
          assertEquals(false, queued.isCompleted)

          queued.cancel(PayloadCancellationException(payload))
          queued.join()
          payload
        } finally {
          releaseCleanup.complete(Unit)
          first.join()
        }
      }
    }
  }

  @Test(timeout = 10_000)
  fun `test replaced queued load releases callback capture`() {
    checkCancelledQueuedLoadReleasesCapture { loader, queued ->
      val latest = launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { loader.load { } }
      }
      queued.join()
      assertEquals(false, latest.isCompleted)
    }
  }

  @Test(timeout = 10_000)
  fun `test manually cancelled queued load releases callback capture`() {
    checkCancelledQueuedLoadReleasesCapture { loader, queued ->
      val cancelling = launch(start = CoroutineStart.UNDISPATCHED) { loader.cancelAndJoin() }
      queued.join()
      assertEquals(false, cancelling.isCompleted)
    }
  }

  @Test(timeout = 10_000)
  fun `test cancelled queued load releases callback capture`() {
    checkCancelledQueuedLoadReleasesCapture { _, queued ->
      queued.cancel(PayloadCancellationException(Any()))
      queued.join()
    }
  }

  @Test(timeout = 10_000)
  fun `test cancelled queued load releases callback capture during previous cleanup`() {
    checkCancelledQueuedLoadReleasesReferenceDuringCleanup(captureCallback = true)
  }

  @Test(timeout = 10_000)
  fun `test cancelled queued load releases cancellation payload during previous cleanup`() {
    checkCancelledQueuedLoadReleasesReferenceDuringCleanup(captureCallback = false)
  }

  @Test(timeout = 10_000)
  fun `test replaced queued load releases callback capture during previous cleanup`() {
    checkEndedQueuedLoadReleasesCaptureDuringCleanup { loader, queued ->
      val latest = launch(start = CoroutineStart.UNDISPATCHED) {
        runCatching { loader.load { } }
      }
      queued.join()
      assertEquals(false, latest.isCompleted)
    }
  }

  @Test(timeout = 10_000)
  fun `test manually cancelled queued load releases callback capture during previous cleanup`() {
    checkEndedQueuedLoadReleasesCaptureDuringCleanup { loader, queued ->
      val cancelling = launch(start = CoroutineStart.UNDISPATCHED) { loader.cancelAndJoin() }
      queued.join()
      assertEquals(false, cancelling.isCompleted)
    }
  }

  // 旧任务仍在清理时，替换排队加载的 load 和 cancelAndJoin 都在等待清理，它们不能继续持有已结束的排队加载
  private fun checkEndedQueuedLoadReleasesCaptureDuringCleanup(endQueued: suspend CoroutineScope.(loader: FLoader, queued: Job) -> Unit) {
    val loader = FLoader()
    val scopeJob = SupervisorJob()
    // 独立根 scope 让旧任务和结束排队加载的调用在 runTest 退出后的 GC 检查期间继续等待清理
    val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val first = scope.launch(start = CoroutineStart.UNDISPATCHED) {
      loader.loadForTest {
        try {
          awaitCancellation()
        } finally {
          withContext(NonCancellable) {
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
          }
        }
      }.getOrThrow()
    }

    try {
      assertReleased(loader) {
        val capture = Any()
        val queued = scope.launch(start = CoroutineStart.UNDISPATCHED) {
          runCatching { load { capture.hashCode() } }
        }
        assertEquals(true, cleanupStarted.isCompleted)
        assertEquals(false, queued.isCompleted)

        scope.endQueued(this, queued)
        assertEquals(true, queued.isCompleted)
        assertEquals(false, first.isCompleted)
        assertEquals(true, isBusyFlow.value)
        capture
      }
      assertEquals(false, first.isCompleted)
      assertEquals(true, loader.isBusyFlow.value)
    } finally {
      releaseCleanup.complete(Unit)
      runTest { scopeJob.cancelAndJoin() }
    }
  }

  private fun checkCancelledQueuedLoadReleasesReferenceDuringCleanup(captureCallback: Boolean) {
    val loader = FLoader()
    val scopeJob = SupervisorJob()
    // 独立根 scope 让旧任务在 runTest 退出后的 GC 检查期间继续等待清理
    val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val first = scope.launch(start = CoroutineStart.UNDISPATCHED) {
      loader.loadForTest {
        try {
          awaitCancellation()
        } finally {
          withContext(NonCancellable) {
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
          }
        }
      }.getOrThrow()
    }

    try {
      assertReleased(loader) {
        val payload = Any()
        // 分别验证回调捕获和取消原因持有的对象，避免两条引用路径混在一起
        val onLoad: suspend () -> Int = if (captureCallback) {
          { payload.hashCode() }
        } else {
          { error("Queued load must not run") }
        }
        val queued = scope.launch(start = CoroutineStart.UNDISPATCHED) { load(onLoad).getOrThrow() }
        assertEquals(true, cleanupStarted.isCompleted)
        assertEquals(false, queued.isCompleted)

        queued.cancel(PayloadCancellationException(if (captureCallback) Any() else payload))
        queued.join()
        assertEquals(true, queued.isCompleted)
        assertEquals(false, first.isCompleted)
        assertEquals(true, isBusyFlow.value)
        payload
      }
      assertEquals(false, first.isCompleted)
      assertEquals(true, loader.isBusyFlow.value)
    } finally {
      releaseCleanup.complete(Unit)
      runTest { scopeJob.cancelAndJoin() }
    }
  }

  // 排队加载被替换、被 cancelAndJoin 取消或被调用方取消后，Loader 不能继续持有它的回调闭包
  private fun checkCancelledQueuedLoadReleasesCapture(cancelQueued: suspend CoroutineScope.(loader: FLoader, queued: Job) -> Unit) {
    assertReleased(FLoader()) {
      coroutineScope {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
          loadForTest {
            try {
              awaitCancellation()
            } finally {
              withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
              }
            }
          }
        }

        try {
          val capture = Any()
          val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { load { capture.hashCode() } }
          }
          cleanupStarted.await()
          assertEquals(false, first.isCompleted)
          assertEquals(false, queued.isCompleted)

          cancelQueued(this@assertReleased, queued)
          assertEquals(true, queued.isCompleted)
          assertEquals(false, first.isCompleted)
          capture
        } finally {
          releaseCleanup.complete(Unit)
          first.join()
        }
      }
    }
  }

  // 运行中的加载被别处取消后，Loader 不能继续持有它的回调闭包
  private fun checkCancelledLoadReleasesCapture(replace: Boolean) {
    assertReleased(FLoader()) {
      coroutineScope {
        val capture = Any()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
          runCatching {
            loadForTest {
              capture.hashCode()
              awaitCancellation()
            }
          }
        }
        if (replace) load { }.getOrThrow() else cancelAndJoin()
        first.join()
        capture
      }
    }
  }

  // 旧加载被替换并结束后，替换它的新加载仍在运行，不能继续持有旧加载的回调闭包
  private fun checkReplacedLoadReleasesCaptureWhileNextLoadRunning(cleanup: Boolean) {
    val loader = FLoader()
    val scopeJob = SupervisorJob()
    // 独立根 scope 让新加载在 runTest 退出后的 GC 检查期间继续运行
    val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
    val releaseCleanup = CompletableDeferred<Unit>()
    val releaseNext = CompletableDeferred<Unit>()

    try {
      assertReleased(loader) {
        val capture = Any()
        val first = scope.launch(start = CoroutineStart.UNDISPATCHED) {
          runCatching {
            loadForTest {
              capture.hashCode()
              try {
                awaitCancellation()
              } finally {
                if (cleanup) withContext(NonCancellable) { releaseCleanup.await() }
              }
            }
          }
        }
        val next = scope.launch(start = CoroutineStart.UNDISPATCHED) {
          load { releaseNext.await() }
        }
        // 旧加载清理挂起时新加载先等待它结束，否则旧加载在新加载的取消调用内联结束
        assertEquals(!cleanup, first.isCompleted)

        releaseCleanup.complete(Unit)
        first.join()
        assertEquals(false, next.isCompleted)
        assertEquals(true, isBusyFlow.value)
        capture
      }
      assertEquals(true, loader.isBusyFlow.value)
    } finally {
      releaseCleanup.complete(Unit)
      releaseNext.complete(Unit)
      runTest { scopeJob.cancelAndJoin() }
    }
  }

  private fun assertReleased(loader: FLoader, block: suspend FLoader.() -> Any) {
    val queue = ReferenceQueue<Any>()
    // 在 runTest 退出后检查回收，避免测试协程自身保留被测对象
    val reference = loadReference(loader, queue, block)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    try {
      do {
        System.gc()
        if (queue.remove(50) === reference) return
      } while (System.nanoTime() < deadline)
      assertNull("Completed load retained its payload", reference.get())
    } finally {
      Reference.reachabilityFence(loader)
    }
  }

  private fun loadReference(loader: FLoader, queue: ReferenceQueue<Any>, block: suspend FLoader.() -> Any): WeakReference<Any> {
    var reference: WeakReference<Any>? = null
    runTest {
      reference = WeakReference(loader.block(), queue)
    }
    return checkNotNull(reference)
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
