package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FLoader
import com.sd.lib.loader.loadingFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class LoaderCallbackTest(private val useTryLoad: Boolean) {

  private class BusinessException(val code: Int) : RuntimeException("child error: $code")

  private class BusinessError(val code: Int) : Error("business error: $code")

  private class CustomCancellationException(val code: Int = 1) : CancellationException("custom cause: $code")

  @Test
  fun `test cancellation during thread context installation prevents callback`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException(3)
    val contextElement = CancelOnContextInstall(cause)
    var callbackEntered = false
    var thrown: Throwable? = null
    val caller = launch(contextElement) {
      contextElement.arm()
      thrown = runCatching { loader.loadForTest { callbackEntered = true } }.exceptionOrNull()
    }
    caller.join()

    assertEquals(true, contextElement.didCancel)
    assertSame(cause, thrown)
    assertEquals(false, callbackEntered)
    assertEquals(false, loader.isLoading())
    assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
  }

  @Test
  fun `test callback Error returns failure and releases loader`() = runTest {
    val loader = FLoader()
    val cause = BusinessError(1)

    val result = loader.loadForTest {
      assertEquals(true, loader.isLoading())
      throw cause
    }

    assertSame(cause, result.exceptionOrNull())
    assertEquals(false, loader.isLoading())
    assertEquals(1, loader.tryLoad { 1 }.getOrThrow())
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test child Error returns failure and releases loader`() = runTest {
    val loader = FLoader()
    val cause = BusinessError(2)

    val result = loader.loadForTest {
      CoroutineScope(currentCoroutineContext()).launch {
        assertEquals(true, loader.isLoading())
        throw cause
      }
      1
    }

    assertSame(cause, result.exceptionOrNull())
    assertEquals(false, loader.isLoading())
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test busy from other loader in child coroutine is swallowed`() = runTest {
    val loader = FLoader()
    val otherLoader = FLoader()
    var childCause: Throwable? = null
    val otherJob = launch { otherLoader.load { delay(Long.MAX_VALUE) } }.also { runCurrent() }

    val result = loader.loadForTest {
      CoroutineScope(currentCoroutineContext()).launch {
        otherLoader.tryLoad { }
      }.invokeOnCompletion { childCause = it }
      1
    }

    // 子协程内的忙异常按子协程取消处理，不会传播到当前加载
    assertTrue(childCause is FLoader.BusyCancellationException)
    assertEquals(1, result.getOrThrow())
    assertEquals(false, loader.isLoading())
    assertEquals(true, otherLoader.isLoading())
    assertEquals(false, otherJob.isCancelled)

    otherJob.cancelAndJoin()
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
  }

  @Test
  fun `test load waits for child success`() = runTest {
    val loader = FLoader()
    val releaseChild = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()

    try {
      loader.loadingFlow.test {
        assertEquals(false, awaitItem())
        val loading = async {
          loader.loadForTest {
            CoroutineScope(currentCoroutineContext()).launch {
              releaseChild.await()
              container.add("child-finished")
            }
            container.add("callback-returned")
            1
          }
        }.also { runCurrent() }

        assertEquals(true, awaitItem())
        assertEquals(listOf("callback-returned"), container)
        assertEquals(false, loading.isCompleted)
        assertEquals(true, loader.isLoading())
        assertTrue(runCatching { loader.tryLoad { 2 } }.exceptionOrNull() is FLoader.BusyCancellationException)
        expectNoEvents()

        releaseChild.complete(Unit)
        assertEquals(1, loading.await().getOrThrow())
        assertEquals(listOf("callback-returned", "child-finished"), container)
        assertEquals(false, awaitItem())
        assertEquals(false, loader.isLoading())
      }
    } finally {
      releaseChild.complete(Unit)
    }
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
  }

  @Test
  fun `test child cancellation does not cancel load or sibling`() = runTest {
    val loader = FLoader()
    val cancelChild = CompletableDeferred<Unit>()
    val releaseSibling = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    var childCause: Throwable? = null

    try {
      loader.loadingFlow.test {
        assertEquals(false, awaitItem())
        val loading = async {
          loader.loadForTest {
            val scope = CoroutineScope(currentCoroutineContext())
            scope.launch {
              cancelChild.await()
              throw CancellationException("child cancelled")
            }.invokeOnCompletion {
              childCause = it
              container.add("child-cancelled")
            }
            scope.launch {
              releaseSibling.await()
              container.add("sibling-finished")
            }
            container.add("callback-returned")
            1
          }
        }.also { runCurrent() }
        assertEquals(true, awaitItem())

        cancelChild.complete(Unit)
        runCurrent()
        assertTrue(childCause is CancellationException)
        assertEquals(listOf("callback-returned", "child-cancelled"), container)
        assertEquals(false, loading.isCompleted)
        assertEquals(true, loader.isLoading())
        assertTrue(runCatching { loader.tryLoad { 2 } }.exceptionOrNull() is FLoader.BusyCancellationException)
        expectNoEvents()

        releaseSibling.complete(Unit)
        assertEquals(1, loading.await().getOrThrow())
        assertEquals(listOf("callback-returned", "child-cancelled", "sibling-finished"), container)
        assertEquals(false, awaitItem())
        assertEquals(false, loader.isLoading())
      }
    } finally {
      cancelChild.complete(Unit)
      releaseSibling.complete(Unit)
    }
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
  }

  @Test
  fun `test child failure waits for sibling cleanup`() = runTest {
    val loader = FLoader()
    val cause = BusinessException(1)
    val failChild = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()

    loader.loadingFlow.test {
      try {
        assertEquals(false, awaitItem())
        val loading = async {
          loader.loadForTest {
            val scope = CoroutineScope(currentCoroutineContext())
            scope.launch {
              try {
                delay(Long.MAX_VALUE)
              } finally {
                withContext(NonCancellable) {
                  cleanupStarted.complete(Unit)
                  releaseCleanup.await()
                }
              }
            }
            scope.launch {
              failChild.await()
              throw cause
            }
            1
          }
        }.also { runCurrent() }
        assertEquals(true, awaitItem())

        failChild.complete(Unit)
        runCurrent()
        assertEquals(true, cleanupStarted.isCompleted)
        assertEquals(false, loading.isCompleted)
        assertEquals(true, loader.isLoading())
        assertTrue(runCatching { loader.tryLoad { 2 } }.exceptionOrNull() is FLoader.BusyCancellationException)
        expectNoEvents()

        releaseCleanup.complete(Unit)
        assertSame(cause, loading.await().exceptionOrNull())
        assertEquals(false, awaitItem())
        assertEquals(false, loader.isLoading())
      } finally {
        // 在 test 等待子协程结束前释放清理信号
        failChild.complete(Unit)
        releaseCleanup.complete(Unit)
      }
    }
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
  }

  @Test
  fun `test callback failure cancels child and waits for cleanup`() = runTest {
    val loader = FLoader()
    val cause = BusinessException(2)
    val childStarted = CompletableDeferred<Unit>()
    val failCallback = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()

    loader.loadingFlow.test {
      assertEquals(false, awaitItem())
      val loading = async {
        loader.loadForTest {
          CoroutineScope(currentCoroutineContext()).launch {
            try {
              childStarted.complete(Unit)
              delay(Long.MAX_VALUE)
            } finally {
              withContext(NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
              }
            }
          }
          childStarted.await()
          failCallback.await()
          throw cause
        }
      }.also { runCurrent() }

      try {
        assertEquals(true, awaitItem())
        assertEquals(true, childStarted.isCompleted)

        failCallback.complete(Unit)
        runCurrent()
        assertEquals(true, cleanupStarted.isCompleted)
        assertEquals(false, loading.isCompleted)
        assertEquals(true, loader.isLoading())
        assertTrue(runCatching { loader.tryLoad { 2 } }.exceptionOrNull() is FLoader.BusyCancellationException)
        expectNoEvents()

        releaseCleanup.complete(Unit)
        assertSame(cause, loading.await().exceptionOrNull())
        assertEquals(false, awaitItem())
        assertEquals(false, loader.isLoading())
      } finally {
        failCallback.complete(Unit)
        releaseCleanup.complete(Unit)
        loading.cancelAndJoin()
      }
    }
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
  }

  @Test
  fun `test callback CancellationException cancels child with same cause and waits for cleanup`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException(4)
    val childStarted = CompletableDeferred<Unit>()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    var childCause: Throwable? = null

    loader.loadingFlow.test {
      assertEquals(false, awaitItem())
      val loading = async {
        runCatching {
          loader.loadForTest {
            CoroutineScope(currentCoroutineContext()).launch {
              try {
                childStarted.complete(Unit)
                awaitCancellation()
              } catch (e: CancellationException) {
                childCause = e
                throw e
              } finally {
                withContext(NonCancellable) {
                  cleanupStarted.complete(Unit)
                  releaseCleanup.await()
                }
                container.add("child-cleaned")
              }
            }
            childStarted.await()
            throw cause
          }
        }.exceptionOrNull()
      }.also { runCurrent() }

      try {
        assertEquals(true, awaitItem())
        // 回调抛出的取消异常原样传给子协程，加载等子协程清理结束后才抛出
        assertEquals(true, cleanupStarted.isCompleted)
        assertSame(cause, childCause)
        assertEquals(false, loading.isCompleted)
        assertEquals(true, loader.isLoading())
        assertEquals(emptyList<String>(), container)
        assertTrue(runCatching { loader.tryLoad { 2 } }.exceptionOrNull() is FLoader.BusyCancellationException)
        expectNoEvents()

        releaseCleanup.complete(Unit)
        assertSame(cause, loading.await())
        assertEquals(false, loading.isCancelled)
        assertEquals(listOf("child-cleaned"), container)
        assertEquals(false, awaitItem())
        assertEquals(false, loader.isLoading())
      } finally {
        releaseCleanup.complete(Unit)
        loading.cancelAndJoin()
      }
    }
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
  }

  @Test
  fun `test cancelAndJoin waits for children after callback returns`() = runTest {
    checkChildCancellation(replace = false)
  }

  @Test
  fun `test replacement waits for children after callback returns`() = runTest {
    checkChildCancellation(replace = true)
  }

  @Test
  fun `test swallowed manual cancellation cannot return result`() = runTest {
    checkSwallowedCancellation(replace = false)
  }

  @Test
  fun `test swallowed replacement cancellation cannot return result`() = runTest {
    checkSwallowedCancellation(replace = true)
  }

  @Test
  fun `test swallowed caller cancellation preserves original cause`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException(2)
    val container = mutableListOf<String>()
    var callbackCause: Throwable? = null
    var loadCause: Throwable? = null

    loader.loadingFlow.test {
      assertEquals(false, awaitItem())
      val loading = launch {
        loadCause = runCatching {
          loader.loadForTest {
            try {
              awaitCancellation()
            } catch (e: CancellationException) {
              callbackCause = e
              container.add("callback-returned")
              1
            }
          }
          container.add("load-returned")
        }.exceptionOrNull()
      }.also { runCurrent() }

      try {
        assertEquals(true, awaitItem())
        assertEquals(false, loading.isCompleted)
        loading.cancel(cause)
        loading.join()

        assertSame(cause, callbackCause)
        assertSame(cause, loadCause)
        assertEquals(true, loading.isCancelled)
        assertEquals(listOf("callback-returned"), container)
        assertEquals(false, awaitItem())
        assertEquals(false, loader.isLoading())
      } finally {
        loading.cancelAndJoin()
      }
    }
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
  }

  @Test
  fun `test outer timeout waits for child cleanup`() = runTest {
    val loader = FLoader()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    var childCause: Throwable? = null

    loader.loadingFlow.test {
      assertEquals(false, awaitItem())
      val loading = async {
        runCatching {
          withTimeout(100) {
            loader.loadForTest {
              CoroutineScope(currentCoroutineContext()).launch {
                try {
                  awaitCancellation()
                } catch (e: CancellationException) {
                  childCause = e
                  throw e
                } finally {
                  withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                  }
                }
              }
              1
            }
          }
        }.exceptionOrNull()
      }.also { runCurrent() }

      try {
        assertEquals(true, awaitItem())
        advanceTimeBy(100)
        runCurrent()
        assertEquals(true, cleanupStarted.isCompleted)
        assertTrue(childCause is TimeoutCancellationException)
        assertEquals(false, loading.isCompleted)
        assertEquals(true, loader.isLoading())
        assertTrue(runCatching { loader.tryLoad { 2 } }.exceptionOrNull() is FLoader.BusyCancellationException)
        expectNoEvents()

        releaseCleanup.complete(Unit)
        assertTrue(loading.await() is TimeoutCancellationException)
        assertEquals(false, awaitItem())
        assertEquals(false, loader.isLoading())
      } finally {
        releaseCleanup.complete(Unit)
        loading.cancelAndJoin()
      }
    }
    assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
  }

  @Test
  fun `test caller cancellation after callback returns waits for child cleanup`() = runTest {
    val loader = FLoader()
    val cause = CustomCancellationException()
    val cleanupStarted = CompletableDeferred<Unit>()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    var childCause: Throwable? = null
    var loadCause: Throwable? = null

    loader.loadingFlow.test {
      assertEquals(false, awaitItem())
      val loading = launch {
        try {
          loader.loadForTest {
            CoroutineScope(currentCoroutineContext()).launch {
              try {
                delay(Long.MAX_VALUE)
              } catch (e: CancellationException) {
                childCause = e
                throw e
              } finally {
                withContext(NonCancellable) {
                  cleanupStarted.complete(Unit)
                  releaseCleanup.await()
                }
                container.add("child-cleaned")
              }
            }
            container.add("callback-returned")
            1
          }
          container.add("load-returned")
        } catch (e: CancellationException) {
          loadCause = e
          throw e
        }
      }.also { runCurrent() }

      try {
        assertEquals(true, awaitItem())
        assertEquals(listOf("callback-returned"), container)
        assertEquals(false, loading.isCompleted)

        loading.cancel(cause)
        runCurrent()
        assertEquals(true, cleanupStarted.isCompleted)
        assertSame(cause, childCause)
        assertEquals(true, loading.isCancelled)
        assertEquals(false, loading.isCompleted)
        assertEquals(null, loadCause)
        assertEquals(true, loader.isLoading())
        assertEquals(listOf("callback-returned"), container)
        assertTrue(runCatching { loader.tryLoad { 2 } }.exceptionOrNull() is FLoader.BusyCancellationException)
        expectNoEvents()

        releaseCleanup.complete(Unit)
        loading.join()
        assertSame(cause, loadCause)
        assertEquals(listOf("callback-returned", "child-cleaned"), container)
        assertEquals(false, awaitItem())
        assertEquals(false, loader.isLoading())
      } finally {
        releaseCleanup.complete(Unit)
        loading.cancelAndJoin()
      }
    }
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
  }

  @Test
  fun `test nested load in callback`() = runTest {
    checkNestedCall { load { } }
  }

  @Test
  fun `test nested tryLoad in callback`() = runTest {
    checkNestedCall { tryLoad { } }
  }

  @Test
  fun `test nested cancelAndJoin in callback`() = runTest {
    checkNestedCall { cancelAndJoin() }
  }

  @Test
  fun `test emit in callback returns failure and releases loader`() = runTest {
    val loader = FLoader()
    var cause: Throwable? = null
    flow<Int> {
      loader.loadForTest { emit(1) }.also { cause = it.exceptionOrNull() }
    }.test {
      awaitComplete()
    }

    assertTrue(cause is IllegalStateException)
    assertEquals(false, loader.isLoading())
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
  }

  @Test
  fun `test send in callback with channelFlow`() = runTest {
    val loader = FLoader()
    channelFlow {
      loader.loadForTest { send(1) }.getOrThrow()
    }.test {
      assertEquals(1, awaitItem())
      awaitComplete()
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test emit outside callback with flow`() = runTest {
    val loader = FLoader()
    flow {
      emit(loader.loadForTest { 1 }.getOrThrow())
    }.test {
      assertEquals(1, awaitItem())
      awaitComplete()
    }
    assertEquals(false, loader.isLoading())
  }

  private suspend fun <T> FLoader.loadForTest(onLoad: suspend () -> T): Result<T> {
    return if (useTryLoad) tryLoad(onLoad) else load(onLoad)
  }

  private suspend fun checkNestedCall(nested: suspend FLoader.() -> Unit) {
    val loader = FLoader()
    var nestedCause: Throwable? = null

    val result = loader.loadForTest {
      nestedCause = runCatching { loader.nested() }.exceptionOrNull()
      1
    }

    // 嵌套调用被拦截，不影响当前加载的结果
    assertTrue(nestedCause is IllegalStateException)
    assertEquals("Nested invoke", nestedCause?.message)
    assertEquals(1, result.getOrThrow())
    assertEquals(false, loader.isLoading())
    assertEquals(2, loader.tryLoad { 2 }.getOrThrow())
  }

  private suspend fun TestScope.checkSwallowedCancellation(replace: Boolean) {
    val loader = FLoader()
    val container = mutableListOf<String>()
    var callbackCause: Throwable? = null
    val loading = async {
      runCatching {
        loader.loadForTest {
          try {
            awaitCancellation()
          } catch (e: CancellationException) {
            callbackCause = e
            container.add("callback-returned")
            1
          }
        }
        container.add("load-returned")
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      assertEquals(true, loader.isLoading())
      assertEquals(false, loading.isCompleted)
      if (replace) {
        loader.load {
          container.add("new-load")
          2
        }.also { assertEquals(2, it.getOrThrow()) }
      } else {
        loader.cancelAndJoin()
      }

      val cause = loading.await()
      assertTrue(if (replace) callbackCause is FLoader.ReplacedCancellationException else callbackCause is FLoader.ManualCancellationException)
      assertTrue(if (replace) cause is FLoader.ReplacedCancellationException else cause is FLoader.ManualCancellationException)
      val expected = if (replace) listOf("callback-returned", "new-load") else listOf("callback-returned")
      assertEquals(expected, container)
      assertEquals(false, loader.isLoading())
    } finally {
      loading.cancelAndJoin()
    }
    assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
  }

  private suspend fun TestScope.checkChildCancellation(replace: Boolean) {
    val loader = FLoader()
    val releaseCleanup = CompletableDeferred<Unit>()
    val container = mutableListOf<String>()
    var childCause: CancellationException? = null
    val loading = async {
      runCatching {
        loader.loadForTest {
          CoroutineScope(currentCoroutineContext()).launch {
            try {
              delay(Long.MAX_VALUE)
            } catch (e: CancellationException) {
              childCause = e
              throw e
            } finally {
              withContext(NonCancellable) { releaseCleanup.await() }
              container.add("child-cleaned")
            }
          }
          container.add("callback-returned")
          1
        }
      }.exceptionOrNull()
    }.also { runCurrent() }

    try {
      assertEquals(listOf("callback-returned"), container)
      assertEquals(false, loading.isCompleted)
      val next = async {
        if (replace) {
          loader.load {
            container.add("new-load")
            2
          }.getOrThrow()
        } else {
          loader.cancelAndJoin()
          2
        }
      }.also { runCurrent() }

      assertTrue(if (replace) childCause is FLoader.ReplacedCancellationException else childCause is FLoader.ManualCancellationException)
      assertEquals(false, loading.isCompleted)
      assertEquals(false, next.isCompleted)
      assertEquals(true, loader.isLoading())
      assertEquals(listOf("callback-returned"), container)
      assertTrue(runCatching { loader.tryLoad { 3 } }.exceptionOrNull() is FLoader.BusyCancellationException)

      releaseCleanup.complete(Unit)
      val cause = loading.await()
      assertTrue(if (replace) cause is FLoader.ReplacedCancellationException else cause is FLoader.ManualCancellationException)
      assertEquals(2, next.await())
      val expected = if (replace) listOf("callback-returned", "child-cleaned", "new-load") else listOf("callback-returned", "child-cleaned")
      assertEquals(expected, container)
      assertEquals(false, loader.isLoading())
    } finally {
      releaseCleanup.complete(Unit)
    }
    assertEquals(3, loader.tryLoad { 3 }.getOrThrow())
  }

  private class CancelOnContextInstall(private val cause: CancellationException) : ThreadContextElement<Unit>, AbstractCoroutineContextElement(Key) {
    private var _armed = false
    var didCancel = false
      private set

    fun arm() {
      _armed = true
    }

    override fun updateThreadContext(context: CoroutineContext) {
      if (_armed) {
        _armed = false
        context[Job]!!.cancel(cause)
        didCancel = true
      }
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) = Unit

    companion object Key : CoroutineContext.Key<CancelOnContextInstall>
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "tryLoad={0}")
    fun parameters(): List<Boolean> = listOf(false, true)
  }
}
