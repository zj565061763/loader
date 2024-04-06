package com.sd.demo.loader

import com.sd.lib.loader.FLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        // onLoad
        loader.load { error("failure") }.let { result ->
            assertEquals("failure", result.exceptionOrNull()!!.message)
        }

        // onStart
        loader.load(
            onStart = { error("failure") },
        ) { 1 }.let { result ->
            assertEquals("failure", result.exceptionOrNull()!!.message)
        }

        // onSuccess
        loader.load(
            onSuccess = { error("failure") },
        ) { 1 }.let { result ->
            assertEquals("failure", result.exceptionOrNull()!!.message)
        }

        // onFailure
        try {
            loader.load(
                onFailure = { error("failure") },
            ) { error("load failure") }
        } catch (e: Exception) {
            assertEquals("failure", e.message)
        }

        // onFinish
        try {
            loader.load(
                onFinish = { error("failure") },
            ) { 1 }
        } catch (e: Exception) {
            assertEquals("failure", e.message)
        }
    }

    @Test
    fun `test load cancel`(): Unit = runBlocking {
        val loader = FLoader()

        val job = launch {
            loader.load {
                delay(2_000)
                1
            }
        }

        delay(1_000)
        loader.cancelLoad()
        assertEquals(true, job.isCancelled)
    }

    @Test
    fun `test load when loading`(): Unit = runBlocking {
        val loader = FLoader()

        val job = launch {
            loader.load {
                delay(2_000)
                1
            }
        }

        delay(1_000)
        loader.load { 2 }.let { result ->
            assertEquals(2, result.getOrThrow())
            assertEquals(true, job.isCancelled)
        }
    }

    @Test
    fun `test callback load success`(): Unit = runBlocking {
        val loader = FLoader()
        mutableListOf<String>().let { listCallback ->
            loader.load(
                onStart = { listCallback.add("onStart") },
                onFinish = { listCallback.add("onFinish") },
                onSuccess = { listCallback.add("onSuccess") },
                onFailure = { listCallback.add("onFailure") },
                onLoad = { listCallback.add("onLoad") },
            ).let { result ->
                assertEquals("onStart|onLoad|onSuccess|onFinish", listCallback.joinToString("|"))
            }
        }
    }

    @Test
    fun `test callback load failure`(): Unit = runBlocking {
        val loader = FLoader()

        // onLoad
        mutableListOf<String>().let { listCallback ->
            loader.load(
                onStart = { listCallback.add("onStart") },
                onFinish = { listCallback.add("onFinish") },
                onSuccess = { listCallback.add("onSuccess") },
                onFailure = { listCallback.add("onFailure") },
                onLoad = {
                    listCallback.add("onLoad")
                    error("failure")
                },
            ).let { result ->
                assertEquals("onStart|onLoad|onFailure|onFinish", listCallback.joinToString("|"))
            }
        }

        // onStart
        mutableListOf<String>().let { listCallback ->
            loader.load(
                onStart = {
                    listCallback.add("onStart")
                    error("failure")
                },
                onFinish = { listCallback.add("onFinish") },
                onSuccess = { listCallback.add("onSuccess") },
                onFailure = { listCallback.add("onFailure") },
                onLoad = { listCallback.add("onLoad") },
            ).let { result ->
                assertEquals("onStart|onFailure|onFinish", listCallback.joinToString("|"))
            }
        }

        // onSuccess
        mutableListOf<String>().let { listCallback ->
            loader.load(
                onStart = { listCallback.add("onStart") },
                onFinish = { listCallback.add("onFinish") },
                onSuccess = {
                    listCallback.add("onSuccess")
                    error("failure")
                },
                onFailure = { listCallback.add("onFailure") },
                onLoad = { listCallback.add("onLoad") },
            ).let { result ->
                assertEquals("onStart|onLoad|onSuccess|onFailure|onFinish", listCallback.joinToString("|"))
            }
        }

        // onFailure
        mutableListOf<String>().let { listCallback ->
            try {
                loader.load(
                    onStart = { listCallback.add("onStart") },
                    onFinish = { listCallback.add("onFinish") },
                    onSuccess = { listCallback.add("onSuccess") },
                    onFailure = {
                        listCallback.add("onFailure")
                        error("failure")
                    },
                    onLoad = { listCallback.add("onLoad") },
                )
            } catch (e: Exception) {
                assertEquals("failure", e.message)
                assertEquals("onStart|onLoad|onSuccess|onFailure|onFinish", listCallback.joinToString("|"))
            }
        }
    }
}