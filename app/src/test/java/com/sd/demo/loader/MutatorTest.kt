package com.sd.demo.loader

import com.sd.lib.loader.FLoader
import com.sd.lib.loader.FMutator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class MutatorTest {

  @Test(timeout = 10_000)
  fun `test queued task cannot run after newer task registered before cancellation`() = runTest {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { replacementDispatcher ->
      val replacements = AtomicInteger()
      val newestRegistered = CompletableDeferred<Unit>()
      val allowNewestCancel = CountDownLatch(1)
      val cleanupStarted = CompletableDeferred<Unit>()
      val releaseCleanup = CompletableDeferred<Unit>()
      val queuedEntered = AtomicBoolean()
      val mutator = FMutator(newReplaceCause = {
        if (replacements.incrementAndGet() == 2) {
          // 暂停最新任务的取消操作，保留旧排队任务恢复执行的窗口
          newestRegistered.complete(Unit)
          check(allowNewestCancel.await(5, TimeUnit.SECONDS))
        }
        FLoader.ReplacedCancellationException()
      })

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

        allowNewestCancel.countDown()
        assertEquals(3, latest.await())
        assertEquals(4, mutator.mutateOrThrow { 4 })
      } finally {
        allowNewestCancel.countDown()
        releaseCleanup.complete(Unit)
      }
    }
  }
}
