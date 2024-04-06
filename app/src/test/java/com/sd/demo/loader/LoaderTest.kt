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

        // onFailure
        try {
            loader.load(
                onFailure = { error("failure") },
            ) { error("load failure") }
        } catch (e: Throwable) {
            assertEquals("failure", e.message)
        }

        // onFinish
        try {
            loader.load(
                onFinish = { error("failure") },
            ) { 1 }
        } catch (e: Throwable) {
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
        mutableListOf<String>().let { list ->
            loader.load(
                onStart = { list.add("onStart") },
                onFinish = { list.add("onFinish") },
                onFailure = { list.add("onFailure") },
                onLoad = { list.add("onLoad") },
            ).let { result ->
                assertEquals("onStart|onLoad|onFinish", list.joinToString("|"))
            }
        }
    }

    @Test
    fun `test callback load failure`(): Unit = runBlocking {
        val loader = FLoader()

        // onLoad
        mutableListOf<String>().let { list ->
            loader.load(
                onStart = { list.add("onStart") },
                onFinish = { list.add("onFinish") },
                onFailure = { list.add("onFailure") },
                onLoad = {
                    list.add("onLoad")
                    error("failure")
                },
            ).let { result ->
                assertEquals("onStart|onLoad|onFailure|onFinish", list.joinToString("|"))
            }
        }

        // onStart
        mutableListOf<String>().let { list ->
            loader.load(
                onStart = {
                    list.add("onStart")
                    error("failure")
                },
                onFinish = { list.add("onFinish") },
                onFailure = { list.add("onFailure") },
                onLoad = { list.add("onLoad") },
            ).let { result ->
                assertEquals("onStart|onFailure|onFinish", list.joinToString("|"))
            }
        }

        // onFailure
        mutableListOf<String>().let { list ->
            try {
                loader.load(
                    onStart = { list.add("onStart") },
                    onFinish = { list.add("onFinish") },
                    onFailure = {
                        list.add("onFailure")
                        error("failure")
                    },
                    onLoad = { list.add("onLoad") },
                )
            } catch (e: Throwable) {
                assertEquals("failure", e.message)
                assertEquals("onStart|onLoad|onFailure|onFinish", list.joinToString("|"))
            }
        }

        // onFinish
        mutableListOf<String>().let { list ->
            try {
                loader.load(
                    onStart = { list.add("onStart") },
                    onFinish = {
                        list.add("onFinish")
                        error("failure")
                    },
                    onFailure = { list.add("onFailure") },
                    onLoad = { list.add("onLoad") },
                )
            } catch (e: Throwable) {
                assertEquals("failure", e.message)
                assertEquals("onStart|onLoad|onFinish", list.joinToString("|"))
            }
        }
    }

    @Test
    fun `test callback load cancel`(): Unit = runBlocking {
        val loader = FLoader()

        val listCallback = mutableListOf<String>()
        val job = launch {
            loader.load(
                onStart = { listCallback.add("onStart") },
                onFinish = { listCallback.add("onFinish") },
                onFailure = { listCallback.add("onFailure") },
                onLoad = {
                    listCallback.add("onLoad")
                    delay(2_000)
                    1
                },
            )
        }

        delay(1_000)
        loader.cancelLoad()
        assertEquals(true, job.isCancelled)
        assertEquals("onStart|onLoad|onFinish", listCallback.joinToString("|"))
    }

    @Test
    fun `test callback load when loading`(): Unit = runBlocking {
        val loader = FLoader()

        val listCallback = mutableListOf<String>()
        val job = launch {
            loader.load(
                onStart = { listCallback.add("onStart") },
                onFinish = { listCallback.add("onFinish") },
                onFailure = { listCallback.add("onFailure") },
                onLoad = {
                    listCallback.add("onLoad")
                    delay(2_000)
                    1
                },
            )
        }

        delay(1_000)
        mutableListOf<String>().let { list ->
            loader.load(
                onStart = { list.add("onStart") },
                onFinish = { list.add("onFinish") },
                onFailure = { list.add("onFailure") },
                onLoad = { list.add("onLoad") },
            ).let { result ->
                assertEquals("onStart|onLoad|onFinish", list.joinToString("|"))
            }
        }

        assertEquals(true, job.isCancelled)
        assertEquals("onStart|onLoad|onFinish", listCallback.joinToString("|"))
    }
}