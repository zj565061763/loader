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

        // onFinish
        runCatching {
            loader.load(
                onFinish = { error("failure onFinish") },
            ) { 1 }
        }.let { result ->
            assertEquals("failure onFinish", result.exceptionOrNull()!!.message)
        }
    }

    @Test
    fun `test load cancel`(): Unit = runBlocking {
        val loader = FLoader()

        val loading = TestContinuation()
        val job = launch {
            loader.load {
                loading.resume()
                delay(Long.MAX_VALUE)
                1
            }
        }

        loading.await()

        loader.cancelLoad()
        assertEquals(true, job.isCancelled)
        assertEquals(true, job.isCompleted)
    }

    @Test
    fun `test load when loading`(): Unit = runBlocking {
        val loader = FLoader()

        val loading = TestContinuation()
        val job = launch {
            loader.load {
                loading.resume()
                delay(Long.MAX_VALUE)
                1
            }
        }

        loading.await()

        loader.load { 2 }.let { result ->
            assertEquals(2, result.getOrThrow())
            assertEquals(true, job.isCancelled)
            assertEquals(true, job.isCompleted)
        }
    }

    @Test
    fun `test callback load success`(): Unit = runBlocking {
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
    fun `test callback load failure`(): Unit = runBlocking {
        val loader = FLoader()

        // onLoad
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

        // onFinish
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
    fun `test callback load cancel`(): Unit = runBlocking {
        val loader = FLoader()

        val listCallback = mutableListOf<String>()
        val loading = TestContinuation()

        launch {
            loader.load(
                onFinish = { listCallback.add("onFinish") },
                onLoad = {
                    listCallback.add("onLoad")
                    loading.resume()
                    delay(Long.MAX_VALUE)
                    1
                },
            )
        }

        loading.await()

        loader.cancelLoad()
        assertEquals("onLoad|onFinish", listCallback.joinToString("|"))
    }

    @Test
    fun `test callback load when loading`(): Unit = runBlocking {
        val loader = FLoader()

        val listCallback = mutableListOf<String>()
        val loading = TestContinuation()

        launch {
            loader.load(
                onFinish = { listCallback.add("onFinish") },
                onLoad = {
                    listCallback.add("onLoad")
                    loading.resume()
                    delay(Long.MAX_VALUE)
                    1
                },
            )
        }

        loading.await()

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