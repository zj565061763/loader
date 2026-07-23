package com.sd.demo.loader

import com.sd.lib.loader.FMutator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class MutatorTest {

  @Test
  fun `test mutate`() = runTest {
    val mutator = FMutator()
    val result = mutator.mutate { 1 }
    assertEquals(1, result)
  }

  @Test
  fun `test mutate when error in block`() = runTest {
    val mutator = FMutator()
    runCatching {
      mutator.mutate { error("error in block") }
    }.also { result ->
      assertEquals("error in block", result.exceptionOrNull()!!.message)
    }
    // 出错后仍可再次使用
    assertEquals(2, mutator.mutate { 2 })
  }

  @Test
  fun `test mutate cancels previous`() = runTest {
    val mutator = FMutator()
    var container = ""

    val job = launch {
      mutator.mutate {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also { runCurrent() }

    mutator.mutate {
      assertEquals("1", container)
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      container += "2"
    }
    assertEquals("12", container)
  }

  @Test
  fun `test mutateOrThrow when idle`() = runTest {
    val mutator = FMutator()
    val result = mutator.mutateOrThrow { 1 }
    assertEquals(1, result)
  }

  @Test
  fun `test mutateOrThrow when busy`() = runTest {
    val mutator = FMutator()
    var container = ""

    val job = launch {
      mutator.mutate {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also { runCurrent() }

    runCatching {
      mutator.mutateOrThrow { 9 }
    }.also { result ->
      assertEquals(true, result.exceptionOrNull() is FMutator.BusyException)
    }

    // busy 时不应取消进行中的任务
    assertEquals("", container)
    assertEquals(false, job.isCancelled)

    job.cancelAndJoin()
    assertEquals("1", container)
  }

  @Test
  fun `test cancelAndJoin`() = runTest {
    val mutator = FMutator()
    var container = ""

    launch {
      mutator.mutate {
        try {
          delay(Long.MAX_VALUE)
        } finally {
          container += "1"
        }
      }
    }.also { job ->
      runCurrent()
      mutator.cancelAndJoin()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
      assertEquals("1", container)
    }
  }

  @Test
  fun `test cancelAndJoin when idle`() = runTest {
    val mutator = FMutator()
    // 空闲时取消不应抛异常，且之后仍可使用
    mutator.cancelAndJoin()
    assertEquals(1, mutator.mutate { 1 })
  }

  @Test
  fun `test mutate when throw CancellationException in block`() = runTest {
    val mutator = FMutator()
    launch {
      mutator.mutate {
        throw CancellationException()
      }
    }.also { job ->
      runCurrent()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
    }
  }

  @Test
  fun `test mutate when cancel in block`() = runTest {
    val mutator = FMutator()
    launch {
      mutator.mutate {
        currentCoroutineContext().cancel()
      }
    }.also { job ->
      runCurrent()
      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
    }
  }

  @Test
  fun `test nested mutate`() = runTest {
    val mutator = FMutator()
    var message = "none"
    mutator.mutate {
      runCatching {
        mutator.mutate { }
      }.also {
        message = it.exceptionOrNull()!!.message!!
      }
    }
    assertEquals("Nested invoke", message)
  }

  @Test
  fun `test nested mutateOrThrow`() = runTest {
    val mutator = FMutator()
    var message = "none"
    mutator.mutate {
      runCatching {
        mutator.mutateOrThrow { }
      }.also {
        message = it.exceptionOrNull()!!.message!!
      }
    }
    assertEquals("Nested invoke", message)
  }

  @Test
  fun `test nested mutate with other mutator`() = runTest {
    val mutator = FMutator()
    val otherMutator = FMutator()

    mutator.mutate {
      otherMutator.mutate {
        runCatching {
          mutator.mutate { }
        }.also {
          assertEquals("Nested invoke", it.exceptionOrNull()!!.message)
        }
      }
    }
  }
}
