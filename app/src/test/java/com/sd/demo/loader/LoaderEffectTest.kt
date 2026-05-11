package com.sd.demo.loader

import com.sd.lib.loader.FLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoaderEffectTest {

  /** 按顺序执行 */
  @Test
  fun testEffectOrder() = runTest {
    val loader = FLoader()
    val results = mutableListOf<Int>()

    launch {
      loader.effect {
        delay(100)
        results.add(1)
      }
    }.also { runCurrent() }

    launch {
      loader.effect {
        delay(50)
        results.add(2)
      }
    }.also { runCurrent() }

    delay(500)
    Assert.assertEquals(listOf(1, 2), results)
  }

  /** 如果调用[FLoader.effect]时，[FLoader.load]或[FLoader.tryLoad]正在执行，则当前协程会挂起 */
  @Test
  fun testEffectSuspendsWhenLoading() = runTest {
    val loader = FLoader()
    var effectStarted = false

    launch {
      loader.load {
        delay(200)
      }
    }.also { runCurrent() }

    launch {
      loader.effect {
        effectStarted = true
      }
    }.also { runCurrent() }

    // 此时 load 还在运行，effect 应该还没开始
    Assert.assertTrue("Effect should not have started while load is in progress", !effectStarted)

    delay(500)
    // load 完成后，effect 应该执行了
    Assert.assertTrue("Effect should have started after load finished", effectStarted)
  }

  /** [FLoader.load]或[FLoader.tryLoad]执行时，会取消所有[FLoader.effect]协程 */
  @Test
  fun testLoadCancelsEffects() = runTest {
    val loader = FLoader()
    var effect1Cancelled = false
    var effect2Cancelled = false

    val job1 = launch {
      try {
        loader.effect {
          delay(500)
        }
      } catch (e: CancellationException) {
        effect1Cancelled = true
        throw e
      }
    }.also { runCurrent() }

    val job2 = launch {
      try {
        loader.effect {
          delay(500)
        }
      } catch (e: CancellationException) {
        effect2Cancelled = true
        throw e
      }
    }.also { runCurrent() }

    loader.load {}

    Assert.assertTrue("Effect 1 should be cancelled", effect1Cancelled)
    Assert.assertTrue("Effect 2 should be cancelled", effect2Cancelled)
    Assert.assertTrue("Effect 1 job should be cancelled", job1.isCancelled)
    Assert.assertTrue("Effect 2 job should be cancelled", job2.isCancelled)
  }
}