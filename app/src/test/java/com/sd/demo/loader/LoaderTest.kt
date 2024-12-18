package com.sd.demo.loader

import com.sd.lib.loader.FLoader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoaderTest {

   @Test
   fun `test load success`() = runTest {
      val loader = FLoader()
      loader.load {
         1
      }.let { result ->
         assertEquals(1, result.getOrThrow())
         assertEquals(true, loader.state.result?.isSuccess)
      }
   }

   @Test
   fun `test load failure`() = runTest {
      val loader = FLoader()
      loader.load {
         error("load error")
      }.let { result ->
         assertEquals("load error", result.exceptionOrNull()!!.message)
         assertEquals("load error", loader.state.result!!.exceptionOrNull()!!.message)
      }
   }

   @Test
   fun `test loading callback true`() = runTest {
      val loader = FLoader(notifyLoading = { true })

      launch {
         loader.load {
            delay(1_000)
         }
      }

      runCurrent()
      assertEquals(true, loader.isLoading)

      advanceUntilIdle()
      assertEquals(false, loader.isLoading)
   }

   @Test
   fun `test loading callback false`() = runTest {
      val loader = FLoader(notifyLoading = { false })

      launch {
         loader.load {
            delay(1_000)
         }
      }

      runCurrent()
      assertEquals(false, loader.isLoading)

      advanceUntilIdle()
      assertEquals(false, loader.isLoading)
   }

   @Test
   fun `test loading params true`() = runTest {
      testLoadingParamsTrue(FLoader(notifyLoading = { true }))
      testLoadingParamsTrue(FLoader(notifyLoading = { false }))
   }

   @Test
   fun `test loading params false`() = runTest {
      testLoadingParamsFalse(FLoader(notifyLoading = { true }))
      testLoadingParamsFalse(FLoader(notifyLoading = { false }))
   }

   @Test
   fun `test onFinish error`() = runTest {
      val loader = FLoader()
      runCatching {
         loader.load(
            onFinish = { error("onFinish error") },
         ) {
            1
         }
      }.let { result ->
         assertEquals("onFinish error", result.exceptionOrNull()!!.message)
      }
   }

   @Test
   fun `test load cancel`() = runTest {
      val loader = FLoader()

      val job = launch {
         loader.load {
            delay(Long.MAX_VALUE)
            1
         }
      }

      runCurrent()
      loader.cancelLoad()

      assertEquals(true, job.isCancelled)
      assertEquals(true, job.isCompleted)
   }

   @Test
   fun `test load when loading`() = runTest {
      val loader = FLoader()

      val job = launch {
         loader.load {
            delay(Long.MAX_VALUE)
            1
         }
      }

      runCurrent()

      loader.load { 2 }.let { result ->
         assertEquals(2, result.getOrThrow())
         assertEquals(true, job.isCancelled)
         assertEquals(true, job.isCompleted)
      }
   }

   @Test
   fun `test callback load success`() = runTest {
      val loader = FLoader()
      mutableListOf<String>().let { list ->
         loader.load(
            onFinish = { list.add("onFinish") },
            onLoad = { list.add("onLoad") },
         ).let {
            assertEquals("onLoad|onFinish", list.joinToString("|"))
         }
      }
   }

   @Test
   fun `test callback load failure`() = runTest {
      val loader = FLoader()
      mutableListOf<String>().let { list ->
         loader.load(
            onFinish = { list.add("onFinish") },
            onLoad = {
               list.add("onLoad")
               error("failure")
            },
         ).let {
            assertEquals("onLoad|onFinish", list.joinToString("|"))
         }
      }
   }

   @Test
   fun `test callback onFinish error`() = runTest {
      val loader = FLoader()
      mutableListOf<String>().let { list ->
         runCatching {
            loader.load(
               onFinish = {
                  list.add("onFinish")
                  error("failure onFinish")
               },
               onLoad = { list.add("onLoad") },
            )
         }.let { result ->
            assertEquals("failure onFinish", result.exceptionOrNull()!!.message)
            assertEquals("onLoad|onFinish", list.joinToString("|"))
         }
      }
   }

   @Test
   fun `test callback load cancel`() = runTest {
      val loader = FLoader()
      val listCallback = mutableListOf<String>()

      launch {
         loader.load(
            onFinish = { listCallback.add("onFinish") },
            onLoad = {
               listCallback.add("onLoad")
               delay(Long.MAX_VALUE)
               1
            },
         )
      }

      runCurrent()
      loader.cancelLoad()

      assertEquals("onLoad|onFinish", listCallback.joinToString("|"))
   }

   @Test
   fun `test callback load when loading`() = runTest {
      val loader = FLoader()
      val listCallback = mutableListOf<String>()

      launch {
         loader.load(
            onFinish = { listCallback.add("onFinish") },
            onLoad = {
               listCallback.add("onLoad")
               delay(Long.MAX_VALUE)
               1
            },
         )
      }

      runCurrent()

      mutableListOf<String>().let { list ->
         loader.load(
            onFinish = { list.add("onFinish") },
            onLoad = { list.add("onLoad") },
         ).let {
            assertEquals("onLoad|onFinish", list.joinToString("|"))
         }
      }

      assertEquals("onLoad|onFinish", listCallback.joinToString("|"))
   }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.testLoadingParamsTrue(loader: FLoader) {
   launch {
      loader.load(notifyLoading = true) {
         delay(1_000)
      }
   }

   runCurrent()
   assertEquals(true, loader.isLoading)

   advanceUntilIdle()
   assertEquals(false, loader.isLoading)
}


@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.testLoadingParamsFalse(loader: FLoader) {
   launch {
      loader.load(notifyLoading = false) {
         delay(1_000)
      }
   }

   runCurrent()
   assertEquals(false, loader.isLoading)

   advanceUntilIdle()
   assertEquals(false, loader.isLoading)
}