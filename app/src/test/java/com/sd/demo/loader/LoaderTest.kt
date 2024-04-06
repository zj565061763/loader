package com.sd.demo.loader

import com.sd.lib.loader.FLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LoaderTest {
    @Test
    fun `test load success`(): Unit = runBlocking {
        val loader = FLoader()
        loader.load { 1 }.let { result ->
            assertEquals(1, result.getOrThrow())
        }
    }

    @Test
    fun `test load failure`(): Unit = runBlocking {
        val loader = FLoader()

        loader.load { error("failure") }.let { result ->
            assertEquals("failure", result.exceptionOrNull()!!.message)
        }

        loader.load(
            onStart = { error("failure") },
        ) { 1 }.let { result ->
            assertEquals("failure", result.exceptionOrNull()!!.message)
        }

        loader.load(
            onSuccess = { error("failure") },
        ) { 1 }.let { result ->
            assertEquals("failure", result.exceptionOrNull()!!.message)
        }

        try {
            loader.load(
                onFailure = { error("failure") },
            ) { error("load failure") }
        } catch (e: Exception) {
            assertEquals("failure", e.message)
        }

        try {
            loader.load(
                onFinish = { error("failure") },
            ) { 1 }
        } catch (e: Exception) {
            assertEquals("failure", e.message)
        }
    }

    @Test
    fun `test callback success`(): Unit = runBlocking {
        val loader = FLoader()

        var callbackString = ""

        loader.load(
            onStart = {
                callbackString += "onStart"
            },
            onFinish = {
                callbackString += "onFinish"
            },
            onSuccess = {
                callbackString += "onSuccess"
            },
            onFailure = {
                callbackString += "onError"
            },
            onLoad = {
                callbackString += "onLoad"
                1
            },
        ).let { result ->
            assertEquals(1, result.getOrThrow())
            assertEquals("onStartonLoadonSuccessonFinish", callbackString)
        }
    }
}