package com.sd.demo.loader

import com.sd.lib.loader.FLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
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
