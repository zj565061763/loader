package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FDataLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DataLoaderTest {
    @Test
    fun `test default state`() {
        FDataLoader(0).state.run {
            assertEquals(0, data)
            assertEquals(null, result)
            assertEquals(false, isLoading)
        }
    }

    @Test
    fun `test load success`(): Unit = runBlocking {
        val loader = FDataLoader(0)
        loader.load { 1 }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(1, result.getOrThrow())
        }
        loader.state.run {
            assertEquals(1, data)
            assertEquals(Result.success(Unit), result)
            assertEquals(false, isLoading)
        }
    }

    @Test
    fun `test load failure`(): Unit = runBlocking {
        val loader = FDataLoader(0)
        loader.load { error("failure") }.let { result ->
            assertEquals(true, result.isFailure)
            assertEquals("failure", result.exceptionOrNull()!!.message)
        }
        loader.state.run {
            assertEquals(0, data)
            assertEquals(true, result!!.isFailure)
            assertEquals("failure", result!!.exceptionOrNull()!!.message)
            assertEquals(false, isLoading)
        }
    }

    @Test
    fun `test load cancel`(): Unit = runBlocking {
        val loader = FDataLoader(0)

        val loading = TestContinuation()
        launch {
            loader.load {
                loading.resume()
                delay(Long.MAX_VALUE)
                1
            }
        }

        loading.await()

        loader.state.run {
            assertEquals(0, data)
            assertEquals(null, result)
            assertEquals(true, isLoading)
        }

        loader.cancelLoad()

        loader.state.run {
            assertEquals(0, data)
            assertEquals(null, result)
            assertEquals(false, isLoading)
        }
    }

    @Test
    fun `test load when loading`(): Unit = runBlocking {
        val loader = FDataLoader(0)

        val loading = TestContinuation()
        launch {
            loader.load {
                loading.resume()
                delay(Long.MAX_VALUE)
                1
            }
        }

        loading.await()

        loader.state.run {
            assertEquals(0, data)
            assertEquals(null, result)
            assertEquals(true, isLoading)
        }

        loader.load { 2 }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(2, result.getOrThrow())
        }

        loader.state.run {
            assertEquals(2, data)
            assertEquals(Result.success(Unit), result)
            assertEquals(false, isLoading)
        }
    }

    @Test
    fun `test notify loading`(): Unit = runBlocking {
        val loader = FDataLoader(0)
        loader.state.run {
            assertEquals(false, isLoading)
        }

        val loading = TestContinuation()
        launch {
            loader.load(notifyLoading = false) {
                loading.resume()
                delay(Long.MAX_VALUE)
                1
            }
        }

        loading.await()
        loader.state.run {
            assertEquals(false, isLoading)
        }

        loader.load(notifyLoading = false) { error("failure") }
        loader.state.run {
            assertEquals(false, isLoading)
        }
    }

    @Test
    fun `test load flow`(): Unit = runBlocking {
        val loader = FDataLoader(0)

        loader.stateFlow.test {
            awaitItem().run {
                assertEquals(0, data)
                assertEquals(null, result)
                assertEquals(false, isLoading)
            }

            loader.load { 1 }

            awaitItem().run {
                assertEquals(0, data)
                assertEquals(null, result)
                assertEquals(true, isLoading)
            }
            awaitItem().run {
                assertEquals(1, data)
                assertEquals(Result.success(Unit), result)
                assertEquals(true, isLoading)
            }
            awaitItem().run {
                assertEquals(1, data)
                assertEquals(Result.success(Unit), result)
                assertEquals(false, isLoading)
            }
        }
    }
}