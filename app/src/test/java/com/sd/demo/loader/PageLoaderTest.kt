package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FPageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PageLoaderTest {
    @Test
    fun `test default state`() {
        val loader = FPageLoader<Int> { page, pageData -> null }
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test refresh success`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        loader.refresh { page ->
            assertEquals(refreshPage, page)
            listOf(1, 2)
        }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(listOf(1, 2), result.getOrThrow())
        }
        loader.state.run {
            assertEquals(listOf(1, 2), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test refresh failure`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        loader.refresh { error("failure") }.let { result ->
            assertEquals(true, result.isFailure)
            assertEquals("failure", result.exceptionOrNull()!!.message)
        }
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals("failure", result!!.exceptionOrNull()!!.message)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test refresh cancel`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        val loading = TestContinuation()
        launch {
            loader.refresh {
                loading.resume()
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        loading.await()
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(true, isRefreshing)
            assertEquals(false, isLoadingMore)
        }

        loader.cancelRefresh()
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test refresh when refreshing`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        val loading = TestContinuation()
        launch {
            loader.refresh {
                loading.resume()
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        loading.await()
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(true, isRefreshing)
            assertEquals(false, isLoadingMore)
        }

        loader.refresh { listOf(3, 4) }
        loader.state.run {
            assertEquals(listOf(3, 4), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test refresh when loadingMore`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        val loading = TestContinuation()
        launch {
            loader.loadMore {
                loading.resume()
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        loading.await()
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(true, isLoadingMore)
        }

        loader.refresh { listOf(3, 4) }
        loader.state.run {
            assertEquals(listOf(3, 4), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test refresh notify loading`(): Unit = runBlocking {
        val loader = FPageLoader<Int> { page, pageData -> null }
        loader.state.run {
            assertEquals(false, isRefreshing)
        }

        val loading = TestContinuation()
        launch {
            loader.refresh(notifyLoading = false) {
                loading.resume()
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        loading.await()
        loader.state.run {
            assertEquals(false, isRefreshing)
        }

        loader.refresh(notifyLoading = false) { error("failure") }
        loader.state.run {
            assertEquals(false, isRefreshing)
        }
    }

    @Test
    fun `test refresh flow`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        loader.stateFlow.test {
            awaitItem().run {
                assertEquals(emptyList<Int>(), data)
                assertEquals(null, result)
                assertEquals(null, page)
                assertEquals(null, pageSize)
                assertEquals(false, isRefreshing)
                assertEquals(false, isLoadingMore)
            }

            loader.refresh { listOf(3, 4) }

            awaitItem().run {
                assertEquals(emptyList<Int>(), data)
                assertEquals(null, result)
                assertEquals(null, page)
                assertEquals(null, pageSize)
                assertEquals(true, isRefreshing)
                assertEquals(false, isLoadingMore)
            }
            awaitItem().run {
                assertEquals(listOf(3, 4), data)
                assertEquals(Result.success(Unit), result)
                assertEquals(refreshPage, page)
                assertEquals(2, pageSize)
                assertEquals(true, isRefreshing)
                assertEquals(false, isLoadingMore)
            }
            awaitItem().run {
                assertEquals(listOf(3, 4), data)
                assertEquals(Result.success(Unit), result)
                assertEquals(refreshPage, page)
                assertEquals(2, pageSize)
                assertEquals(false, isRefreshing)
                assertEquals(false, isLoadingMore)
            }
        }
    }


    @Test
    fun `test loadMore success`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        // 1
        loader.loadMore { page ->
            assertEquals(refreshPage, page)
            listOf(1, 2)
        }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(listOf(1, 2), result.getOrThrow())
        }
        loader.state.run {
            assertEquals(listOf(1, 2), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }

        // 2
        loader.loadMore { page ->
            assertEquals(refreshPage + 1, page)
            listOf(3, 4)
        }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(listOf(3, 4), result.getOrThrow())
        }
        loader.state.run {
            assertEquals(listOf(1, 2, 3, 4), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(refreshPage + 1, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }

        // 3 空数据
        loader.loadMore { page ->
            assertEquals(refreshPage + 2, page)
            emptyList()
        }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(emptyList<Int>(), result.getOrThrow())
        }
        loader.state.run {
            assertEquals(listOf(1, 2, 3, 4), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(refreshPage + 2, page)
            assertEquals(0, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }

        // 4
        loader.loadMore { page ->
            // 由于上一次加载的是空数据，所以此次的page和上一次应该一样
            assertEquals(refreshPage + 2, page)
            emptyList()
        }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(emptyList<Int>(), result.getOrThrow())
        }
        loader.state.run {
            assertEquals(listOf(1, 2, 3, 4), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(refreshPage + 2, page)
            assertEquals(0, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test loadMore failure`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        loader.loadMore { error("failure") }.let { result ->
            assertEquals(true, result.isFailure)
            assertEquals("failure", result.exceptionOrNull()!!.message)
        }
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals("failure", result!!.exceptionOrNull()!!.message)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test loadMore cancel`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        val loading = TestContinuation()
        launch {
            loader.loadMore {
                loading.resume()
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        loading.await()
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(true, isLoadingMore)
        }

        loader.cancelLoadMore()
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test loadMore when loadingMore`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        val loading = TestContinuation()
        val loadJob = launch {
            loader.loadMore {
                loading.resume()
                delay(1_000)
                listOf(1, 2)
            }
        }

        loading.await()
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(true, isLoadingMore)
        }

        try {
            loader.loadMore { listOf(3, 4) }
        } catch (e: CancellationException) {
            Result.failure(e)
        }.let { result ->
            assertEquals(true, result.exceptionOrNull()!! is CancellationException)
        }

        loadJob.join()
        loader.state.run {
            assertEquals(listOf(1, 2), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test loadMore when refreshing`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        val loading = TestContinuation()
        val loadJob = launch {
            loader.refresh {
                loading.resume()
                delay(2_000)
                listOf(1, 2)
            }
        }

        loading.await()
        loader.state.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(true, isRefreshing)
            assertEquals(false, isLoadingMore)
        }

        try {
            loader.loadMore { listOf(3, 4) }
        } catch (e: CancellationException) {
            Result.failure(e)
        }.let { result ->
            assertEquals(true, result.exceptionOrNull()!! is CancellationException)
        }

        loadJob.join()
        loader.state.run {
            assertEquals(listOf(1, 2), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test loadMore notify loading`(): Unit = runBlocking {
        val loader = FPageLoader<Int> { page, pageData -> null }
        loader.state.run {
            assertEquals(false, isLoadingMore)
        }

        val loading = TestContinuation()
        launch {
            loader.loadMore(notifyLoading = false) {
                loading.resume()
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        loading.await()
        loader.state.run {
            assertEquals(false, isLoadingMore)
        }

        loader.loadMore(notifyLoading = false) { error("failure") }
        loader.state.run {
            assertEquals(false, isLoadingMore)
        }
    }

    @Test
    fun `test loadMore flow`(): Unit = runBlocking {
        val list = mutableListOf<Int>()
        val loader = FPageLoader { page, pageData ->
            list.apply {
                if (page == refreshPage) clear()
                addAll(pageData)
            }
        }

        loader.stateFlow.test {
            awaitItem().run {
                assertEquals(emptyList<Int>(), data)
                assertEquals(null, result)
                assertEquals(null, page)
                assertEquals(null, pageSize)
                assertEquals(false, isRefreshing)
                assertEquals(false, isLoadingMore)
            }

            loader.loadMore { listOf(3, 4) }

            awaitItem().run {
                assertEquals(emptyList<Int>(), data)
                assertEquals(null, result)
                assertEquals(null, page)
                assertEquals(null, pageSize)
                assertEquals(false, isRefreshing)
                assertEquals(true, isLoadingMore)
            }
            awaitItem().run {
                assertEquals(listOf(3, 4), data)
                assertEquals(Result.success(Unit), result)
                assertEquals(refreshPage, page)
                assertEquals(2, pageSize)
                assertEquals(false, isRefreshing)
                assertEquals(true, isLoadingMore)
            }
            awaitItem().run {
                assertEquals(listOf(3, 4), data)
                assertEquals(Result.success(Unit), result)
                assertEquals(refreshPage, page)
                assertEquals(2, pageSize)
                assertEquals(false, isRefreshing)
                assertEquals(false, isLoadingMore)
            }
        }
    }
}