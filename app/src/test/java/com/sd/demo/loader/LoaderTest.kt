package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FLoader
import com.sd.lib.loader.loadingFlow
import com.sd.lib.loader.resultFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class LoaderTest {

  @Test
  fun `test load when success`() = runTest {
    val loader = FLoader()
    assertEquals(null, loader.stateFlow.value.result)

    var container = ""
    loader.load {
      assertEquals(true, loader.isLoading())
      onLoadFinish {
        assertEquals(false, loader.isLoading())
        container += "onLoadFinish"
      }
      1
    }.also { result ->
      assertEquals(true, loader.stateFlow.value.result!!.isSuccess)
      assertEquals(1, result.getOrThrow())
      assertEquals("onLoadFinish", container)
    }
  }

  @Test
  fun `test load when error in block`() = runTest {
    val loader = FLoader()

    var container = ""
    loader.load {
      assertEquals(true, loader.isLoading())
      onLoadFinish {
        assertEquals(false, loader.isLoading())
        container += "onLoadFinish"
      }
      error("error in block")
    }.also { result ->
      assertEquals("error in block", result.exceptionOrNull()!!.message)
      assertEquals("error in block", loader.stateFlow.value.result!!.exceptionOrNull()!!.message)
      assertEquals("onLoadFinish", container)
    }
  }

  @Test
  fun `test load when loading`() = runTest {
    val loader = FLoader()
    var container = ""

    val job = launch {
      loader.load {
        assertEquals(true, loader.isLoading())
        onLoadFinish {
          assertEquals(false, loader.isLoading())
          container += "onLoadFinish1"
        }
        delay(Long.MAX_VALUE)
      }
    }.also {
      runCurrent()
    }

    loader.load {
      assertEquals(true, loader.isLoading())
      onLoadFinish {
        assertEquals(false, loader.isLoading())
        container += "onLoadFinish2"
      }
      assertEquals("onLoadFinish1", container)
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      2
    }.also { result ->
      assertEquals(2, result.getOrThrow())
      assertEquals("onLoadFinish1onLoadFinish2", container)
    }
  }

  @Test
  fun `test load when cancel`() = runTest {
    val loader = FLoader()
    var container = ""
    launch {
      loader.load {
        assertEquals(true, loader.isLoading())
        onLoadFinish {
          assertEquals(false, loader.isLoading())
          container += "onLoadFinish"
        }
        delay(Long.MAX_VALUE)
      }
    }.also { job ->
      runCurrent()
      loader.cancel()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals("onLoadFinish", container)
    }
  }

  @Test
  fun `test load when throw CancellationException in block`() = runTest {
    val loader = FLoader()
    var container = ""
    launch {
      loader.load {
        assertEquals(true, loader.isLoading())
        onLoadFinish {
          assertEquals(false, loader.isLoading())
          container += "onLoadFinish"
        }
        throw CancellationException()
      }
    }.also { job ->
      runCurrent()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals("onLoadFinish", container)
    }
  }

  @Test
  fun `test load when cancel in block`() = runTest {
    val loader = FLoader()
    var container = ""
    launch {
      loader.load {
        assertEquals(true, loader.isLoading())
        onLoadFinish {
          assertEquals(false, loader.isLoading())
          container += "onLoadFinish"
        }
        currentCoroutineContext().cancel()
      }
    }.also { job ->
      runCurrent()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals("onLoadFinish", container)
    }
  }

  @Test
  fun `test loadingFlow`() = runTest {
    val loader = FLoader()
    loader.loadingFlow.test {
      loader.load {}
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
    }
  }

  @Test
  fun `test loadingFlow when Reload`() = runTest {
    val loader = FLoader()
    loader.loadingFlow.test {
      launch {
        loader.load { delay(Long.MAX_VALUE) }
      }.also {
        runCurrent()
        loader.load { }
      }
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
    }
  }

  @Test
  fun `test loadingFlow when cancel`() = runTest {
    val loader = FLoader()
    loader.loadingFlow.test {
      launch {
        loader.load { delay(Long.MAX_VALUE) }
      }.also {
        runCurrent()
        loader.cancel()
      }
      assertEquals(false, awaitItem())
      assertEquals(true, awaitItem())
      assertEquals(false, awaitItem())
    }
  }

  @Test
  fun `test resultFlow when load success`() = runTest {
    val loader = FLoader()
    loader.resultFlow.test {
      loader.load { 1 }
      assertEquals(null, awaitItem())
      assertEquals(true, awaitItem()!!.isSuccess)
    }
  }

  @Test
  fun `test resultFlow when load error in block`() = runTest {
    val loader = FLoader()
    loader.resultFlow.test {
      loader.load { error("error in block") }
      assertEquals(null, awaitItem())
      assertEquals("error in block", awaitItem()!!.exceptionOrNull()!!.message)
    }
  }

  @Test
  fun `test resultFlow when load cancel`() = runTest {
    val loader = FLoader()
    loader.resultFlow.test {
      launch {
        loader.load {
          delay(5_000)
          1
        }
      }.also {
        runCurrent()
        loader.cancel()
      }
      assertEquals(null, awaitItem())
    }
  }

  @Test
  fun `test tryLoad`() = runTest {
    val loader = FLoader()

    val job = launch {
      loader.load { delay(Long.MAX_VALUE) }
    }.also {
      runCurrent()
    }

    runCatching {
      loader.tryLoad { 1 }
    }.also { result ->
      assertEquals(true, result.exceptionOrNull() is CancellationException)
    }

    job.cancelAndJoin()
  }

  @Test
  fun `test nested load`() = runTest {
    val loader = FLoader()
    val list = mutableListOf<String>()

    loader.load {
      runCatching {
        loader.load { }
      }.also {
        assertEquals("Nested invoke", it.exceptionOrNull()!!.message)
        list.add("1")
      }
      list.add("2")
    }

    assertEquals(listOf("1", "2"), list)
  }
}