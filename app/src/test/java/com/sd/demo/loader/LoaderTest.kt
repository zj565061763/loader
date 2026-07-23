package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FLoader
import com.sd.lib.loader.loadingFlow
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
    loader.load {
      assertEquals(true, loader.isLoading())
      1
    }.also { result ->
      assertEquals(1, result.getOrThrow())
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when error in block`() = runTest {
    val loader = FLoader()
    loader.load {
      assertEquals(true, loader.isLoading())
      error("error in block")
    }.also { result ->
      assertEquals("error in block", result.exceptionOrNull()!!.message)
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when loading`() = runTest {
    val loader = FLoader()
    var container = ""

    val job = launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also {
      runCurrent()
    }

    loader.load {
      assertEquals(true, loader.isLoading())
      assertEquals("1", container)
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      2
    }.also { result ->
      assertEquals(2, result.getOrThrow())
    }
    assertEquals(false, loader.isLoading())
  }

  @Test
  fun `test load when cancel`() = runTest {
    val loader = FLoader()
    var container = ""
    launch {
      loader.load {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also { job ->
      runCurrent()
      loader.cancel()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals("1", container)
      assertEquals(false, loader.isLoading())
    }
  }

  @Test
  fun `test load when throw CancellationException in block`() = runTest {
    val loader = FLoader()
    launch {
      loader.load {
        assertEquals(true, loader.isLoading())
        throw CancellationException()
      }
    }.also { job ->
      runCurrent()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals(false, loader.isLoading())
    }
  }

  @Test
  fun `test load when cancel in block`() = runTest {
    val loader = FLoader()
    launch {
      loader.load {
        currentCoroutineContext().cancel()
      }
    }.also { job ->
      runCurrent()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals(false, loader.isLoading())
    }
  }

  @Test
  fun `test load when cancel loader in block`() = runTest {
    val loader = FLoader()
    launch {
      loader.load {
        loader.cancel()
      }
    }.also { job ->
      runCurrent()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals(false, loader.isLoading())
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
  fun `test tryLoad`() = runTest {
    val loader = FLoader()

    val job = launch {
      loader.load { delay(Long.MAX_VALUE) }
    }.also {
      runCurrent()
    }

    runCatching { loader.tryLoad { 1 } }.also { result ->
      assertEquals(true, result.exceptionOrNull() is FLoader.BusyCancellationException)
    }
    assertEquals(true, loader.isLoading())

    job.cancelAndJoin()

    loader.tryLoad { 2 }.also { result ->
      assertEquals(2, result.getOrThrow())
    }
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

  @Test
  fun `test nested tryLoad`() = runTest {
    val loader = FLoader()

    loader.load {
      runCatching {
        loader.tryLoad { }
      }.also {
        assertEquals("Nested invoke", it.exceptionOrNull()!!.message)
      }
    }
  }

  @Test
  fun `test nested load with other loader`() = runTest {
    val loader = FLoader()
    val otherLoader = FLoader()

    loader.load {
      otherLoader.load {
        runCatching {
          loader.load { }
        }.also {
          assertEquals("Nested invoke", it.exceptionOrNull()!!.message)
        }
      }
    }
  }

  @Test
  fun `test load other loader in block`() = runTest {
    val loader = FLoader()
    val otherLoader = FLoader()

    loader.load {
      otherLoader.load { 1 }
    }.also { result ->
      assertEquals(1, result.getOrThrow().getOrThrow())
    }
  }
}
