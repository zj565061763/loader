package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.DataState
import com.sd.lib.loader.FLoader
import com.sd.lib.loader.isFailure
import com.sd.lib.loader.isInitial
import com.sd.lib.loader.isSuccess
import com.sd.lib.loader.onFailure
import com.sd.lib.loader.onInitial
import com.sd.lib.loader.onSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LoaderTest {
    @Test
    fun `test default state`() {
        FLoader(0).state.value.run {
            assertEquals(0, data)
            assertEquals(null, result)
            assertEquals(false, isLoading)
            testExtResult(LoaderResultState.Initial)
        }
    }

    @Test
    fun `test load success`(): Unit = runBlocking {
        val loader = FLoader(0)
        loader.load { 1 }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(1, result.getOrThrow())
        }
        loader.state.value.run {
            assertEquals(1, data)
            assertEquals(Result.success(Unit), result)
            assertEquals(false, isLoading)
            testExtResult(LoaderResultState.Success)
        }
    }

    @Test
    fun `test load failure`(): Unit = runBlocking {
        val loader = FLoader(0)
        loader.load { error("failure") }.let { result ->
            assertEquals(true, result.isFailure)
            assertEquals("failure", result.exceptionOrNull()!!.message)
        }
        loader.state.value.run {
            assertEquals(0, data)
            assertEquals(true, result!!.isFailure)
            assertEquals("failure", result!!.exceptionOrNull()!!.message)
            assertEquals(false, isLoading)
            testExtResult(LoaderResultState.Failure)
        }
    }

    @Test
    fun `test load cancel`(): Unit = runBlocking {
        val loader = FLoader(0)

        launch {
            loader.load {
                delay(Long.MAX_VALUE)
                1
            }
        }

        delay(1_000)
        loader.state.value.run {
            assertEquals(0, data)
            assertEquals(null, result)
            assertEquals(true, isLoading)
        }

        loader.cancelLoad()
        loader.state.value.run {
            assertEquals(0, data)
            assertEquals(null, result)
            assertEquals(false, isLoading)
        }
    }

    @Test
    fun `test load when loading`(): Unit = runBlocking {
        val loader = FLoader(0)

        launch {
            loader.load {
                delay(Long.MAX_VALUE)
                1
            }
        }

        delay(1_000)
        loader.state.value.run {
            assertEquals(0, data)
            assertEquals(null, result)
            assertEquals(true, isLoading)
        }

        loader.load { 2 }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(2, result.getOrThrow())
        }
        loader.state.value.run {
            assertEquals(2, data)
            assertEquals(Result.success(Unit), result)
            assertEquals(false, isLoading)
        }
    }

    @Test
    fun `test notify loading`(): Unit = runBlocking {
        val loader = FLoader(0)
        loader.state.value.run {
            assertEquals(false, isLoading)
        }

        launch {
            loader.load(notifyLoading = false) {
                delay(Long.MAX_VALUE)
                1
            }
        }

        delay(1_000)
        loader.state.value.run {
            assertEquals(false, isLoading)
        }

        loader.load(notifyLoading = false) { error("failure") }
        loader.state.value.run {
            assertEquals(false, isLoading)
        }
    }

    @Test
    fun `test load flow`(): Unit = runBlocking {
        val loader = FLoader(0)

        loader.state.test {
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

private fun DataState<*>.testExtResult(state: LoaderResultState) {
    when (state) {
        LoaderResultState.Initial -> {
            assertEquals(true, isInitial)
            assertEquals(false, isSuccess)
            assertEquals(false, isFailure)
            var callbackString = ""
            onInitial { callbackString += "onInitial" }
            onSuccess { callbackString += "onSuccess" }
            onFailure { callbackString += "onFailure" }
            assertEquals("onInitial", callbackString)
        }

        LoaderResultState.Success -> {
            assertEquals(false, isInitial)
            assertEquals(true, isSuccess)
            assertEquals(false, isFailure)
            var callbackString = ""
            onInitial { callbackString += "onInitial" }
            onSuccess { callbackString += "onSuccess" }
            onFailure { callbackString += "onFailure" }
            assertEquals("onSuccess", callbackString)
        }

        LoaderResultState.Failure -> {
            assertEquals(false, isInitial)
            assertEquals(false, isSuccess)
            assertEquals(true, isFailure)
            var callbackString = ""
            onInitial { callbackString += "onInitial" }
            onSuccess { callbackString += "onSuccess" }
            onFailure { callbackString += "onFailure" }
            assertEquals("onFailure", callbackString)
        }
    }
}