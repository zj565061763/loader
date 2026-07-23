package com.sd.demo.loader

import com.sd.lib.loader.FMutex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MutexTest {

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
    }

    // 第二个协程必须等第一个释放锁后才能进入
    assertEquals(listOf("1-start"), container)
    advanceUntilIdle()
    assertEquals(listOf("1-start", "1-end", "2-start", "2-end"), container)
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
  fun `test withLock nested same instance`() = runTest {
    val mutex = FMutex()
    var message = "none"
    mutex.withLock {
      runCatching {
        mutex.withLock { }
      }.also {
        message = it.exceptionOrNull()!!.message!!
      }
    }
    assertEquals("Nested invoke", message)
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

  @Test
  fun `test tryLock when free`() = runTest {
    val mutex = FMutex()
    assertEquals(1, mutex.tryLock { 1 })
  }

  @Test
  fun `test tryLock when locked`() = runTest {
    val mutex = FMutex()
    val job = launch {
      mutex.withLock { delay(Long.MAX_VALUE) }
    }.also { runCurrent() }

    assertEquals(null, mutex.tryLock { 1 })

    job.cancel()
  }

  @Test
  fun `test tryLock releases lock`() = runTest {
    val mutex = FMutex()
    assertEquals(1, mutex.tryLock { 1 })
    // tryLock 结束后锁应已释放
    assertEquals(2, mutex.tryLock { 2 })
    assertEquals(3, mutex.withLock { 3 })
  }

  @Test
  fun `test checkNested outside withLock`() = runTest {
    val mutex = FMutex()
    // 不在 withLock 内部调用不应抛异常
    mutex.checkNested()
  }
}
