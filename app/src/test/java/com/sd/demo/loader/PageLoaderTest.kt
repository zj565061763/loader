package com.sd.demo.loader

import app.cash.turbine.test
import com.sd.lib.loader.FPageLoader
import com.sd.lib.loader.PageState
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

class PageLoaderTest {
    @Test
    fun `test default state`() {
        val loader = FPageLoader<Int> { page, pageData -> null }
        loader.state.value.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Initial)
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

        loader.refresh { listOf(1, 2) }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(listOf(1, 2), result.getOrThrow())
        }
        loader.state.value.run {
            assertEquals(listOf(1, 2), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(loader.refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Success)
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
        loader.state.value.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals("failure", result!!.exceptionOrNull()!!.message)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Failure)
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

        launch {
            loader.refresh {
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        delay(1_000)
        loader.state.value.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(true, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Initial)
        }

        loader.cancelRefresh()
        loader.state.value.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Initial)
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

        launch {
            loader.refresh {
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        delay(1_000)
        loader.state.value.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(true, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Initial)
        }

        loader.refresh { listOf(3, 4) }
        loader.state.value.run {
            assertEquals(listOf(3, 4), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(loader.refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Success)
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

        launch {
            loader.loadMore {
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        delay(1_000)
        loader.state.value.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(true, isLoadingMore)
            testExtResult(LoaderResultState.Initial)
        }

        loader.refresh { listOf(3, 4) }
        loader.state.value.run {
            assertEquals(listOf(3, 4), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(loader.refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Success)
        }
    }

    @Test
    fun `test refresh notify loading`(): Unit = runBlocking {
        val loader = FPageLoader<Int> { page, pageData -> null }
        loader.state.value.run {
            assertEquals(false, isRefreshing)
        }

        launch {
            loader.refresh(notifyLoading = false) {
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        delay(1_000)
        loader.state.value.run {
            assertEquals(false, isRefreshing)
        }

        loader.refresh(notifyLoading = false) { error("failure") }
        loader.state.value.run {
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

        loader.state.test {
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
                assertEquals(loader.refreshPage, page)
                assertEquals(2, pageSize)
                assertEquals(true, isRefreshing)
                assertEquals(false, isLoadingMore)
            }
            awaitItem().run {
                assertEquals(listOf(3, 4), data)
                assertEquals(Result.success(Unit), result)
                assertEquals(loader.refreshPage, page)
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

        loader.loadMore { listOf(1, 2) }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(listOf(1, 2), result.getOrThrow())
        }
        loader.state.value.run {
            assertEquals(listOf(1, 2), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(loader.refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Success)
        }

        loader.loadMore { listOf(3, 4) }.let { result ->
            assertEquals(true, result.isSuccess)
            assertEquals(listOf(3, 4), result.getOrThrow())
        }
        loader.state.value.run {
            assertEquals(listOf(1, 2, 3, 4), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(loader.refreshPage + 1, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Success)
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
        loader.state.value.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals("failure", result!!.exceptionOrNull()!!.message)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Failure)
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

        launch {
            loader.loadMore {
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        delay(1_000)
        loader.state.value.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(true, isLoadingMore)
            testExtResult(LoaderResultState.Initial)
        }

        loader.cancelLoadMore()
        loader.state.value.run {
            assertEquals(emptyList<Int>(), data)
            assertEquals(null, result)
            assertEquals(null, page)
            assertEquals(null, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Initial)
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

        val loadJob = launch {
            loader.loadMore {
                delay(2_000)
                listOf(1, 2)
            }
        }

        launch {
            delay(1_000)
            loader.state.value.run {
                assertEquals(emptyList<Int>(), data)
                assertEquals(null, result)
                assertEquals(null, page)
                assertEquals(null, pageSize)
                assertEquals(false, isRefreshing)
                assertEquals(true, isLoadingMore)
                testExtResult(LoaderResultState.Initial)
            }
            loader.loadMore { listOf(3, 4) }
        }.let { job ->
            job.join()
            assertEquals(true, job.isCancelled)
        }

        loadJob.join()
        loader.state.value.run {
            assertEquals(listOf(1, 2), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(loader.refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Success)
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

        val loadJob = launch {
            loader.refresh {
                delay(2_000)
                listOf(1, 2)
            }
        }

        launch {
            delay(1_000)
            loader.state.value.run {
                assertEquals(emptyList<Int>(), data)
                assertEquals(null, result)
                assertEquals(null, page)
                assertEquals(null, pageSize)
                assertEquals(true, isRefreshing)
                assertEquals(false, isLoadingMore)
                testExtResult(LoaderResultState.Initial)
            }
            loader.loadMore { listOf(3, 4) }
        }.let { job ->
            job.join()
            assertEquals(true, job.isCancelled)
        }

        loadJob.join()
        loader.state.value.run {
            assertEquals(listOf(1, 2), data)
            assertEquals(Result.success(Unit), result)
            assertEquals(loader.refreshPage, page)
            assertEquals(2, pageSize)
            assertEquals(false, isRefreshing)
            assertEquals(false, isLoadingMore)
            testExtResult(LoaderResultState.Success)
        }
    }

    @Test
    fun `test loadMore notify loading`(): Unit = runBlocking {
        val loader = FPageLoader<Int> { page, pageData -> null }
        loader.state.value.run {
            assertEquals(false, isLoadingMore)
        }

        launch {
            loader.loadMore(notifyLoading = false) {
                delay(Long.MAX_VALUE)
                listOf(1, 2)
            }
        }

        delay(1_000)
        loader.state.value.run {
            assertEquals(false, isLoadingMore)
        }

        loader.loadMore(notifyLoading = false) { error("failure") }
        loader.state.value.run {
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

        loader.state.test {
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
                assertEquals(loader.refreshPage, page)
                assertEquals(2, pageSize)
                assertEquals(false, isRefreshing)
                assertEquals(true, isLoadingMore)
            }
            awaitItem().run {
                assertEquals(listOf(3, 4), data)
                assertEquals(Result.success(Unit), result)
                assertEquals(loader.refreshPage, page)
                assertEquals(2, pageSize)
                assertEquals(false, isRefreshing)
                assertEquals(false, isLoadingMore)
            }
        }
    }
}

private fun PageState<*>.testExtResult(state: LoaderResultState) {
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