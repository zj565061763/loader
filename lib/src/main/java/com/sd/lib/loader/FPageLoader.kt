package com.sd.lib.loader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

interface FPageLoader<T> {

    /** 状态 */
    val state: PageState<T>

    /** 状态流 */
    val stateFlow: Flow<PageState<T>>

    /** 刷新数据的页码，例如数据源页码从1开始，那么[refreshPage]就为1 */
    val refreshPage: Int

    /**
     * 刷新
     *
     * @param notifyLoading 是否通知[PageState.isRefreshing]
     * @param onLoad 加载回调
     */
    suspend fun refresh(
        notifyLoading: Boolean = true,
        onLoad: suspend LoadScope<T>.(page: Int) -> List<T>,
    ): Result<List<T>>

    /**
     * 加载更多
     *
     * @param notifyLoading 是否通知[PageState.isLoadingMore]
     * @param onLoad 加载回调
     */
    suspend fun loadMore(
        notifyLoading: Boolean = true,
        onLoad: suspend LoadScope<T>.(page: Int) -> List<T>,
    ): Result<List<T>>

    /**
     * 取消刷新
     */
    suspend fun cancelRefresh()

    /**
     * 取消加载更多
     */
    suspend fun cancelLoadMore()

    /**
     * 设置数据
     */
    fun setData(data: List<T>)

    interface LoadScope<T> {
        /** 当前数据状态 */
        val currentState: PageState<T>

        /** 刷新数据的页码，例如数据源页码从1开始，那么[refreshPage]就为1 */
        val refreshPage: Int
    }
}

/**
 * [FPageLoader]
 *
 * @param initial 初始值
 * @param refreshPage 刷新数据的页码，例如数据源规定页码从1开始，那么此参数就为1
 * @param dataHandler 处理每页的数据，并返回总的数据，返回null则总数据不变化
 */
fun <T> FPageLoader(
    initial: List<T> = emptyList(),
    refreshPage: Int = 1,
    dataHandler: suspend FPageLoader.LoadScope<T>.(page: Int, pageData: List<T>) -> List<T>?,
): FPageLoader<T> {
    return PageLoaderImpl(
        initial = initial,
        refreshPage = refreshPage,
        dataHandler = dataHandler,
    )
}

//-------------------- state --------------------

data class PageState<T>(
    /** 总数据 */
    val data: List<T> = emptyList(),

    /** 最后一次加载的结果 */
    val result: Result<Unit>? = null,

    /** 最后一次加载的页码 */
    val page: Int? = null,

    /** 最后一次加载的数据个数 */
    val pageSize: Int? = null,

    /** 是否正在刷新 */
    val isRefreshing: Boolean = false,

    /** 是否正在加载更多 */
    val isLoadingMore: Boolean = false,
)

/** 是否初始状态 */
val PageState<*>.isInitial: Boolean get() = result == null

/** 是否成功状态 */
val PageState<*>.isSuccess: Boolean get() = result?.isSuccess == true

/** 是否失败状态 */
val PageState<*>.isFailure: Boolean get() = result?.isFailure == true

/** 是否显示没有更多数据 */
val PageState<*>.showNoMoreData: Boolean get() = data.isNotEmpty() && pageSize == 0

/**
 * 初始状态
 */
inline fun <T> PageState<T>.onInitial(action: PageState<T>.() -> Unit): PageState<T> {
    if (result == null) action()
    return this
}

/**
 * 成功状态
 */
inline fun <T> PageState<T>.onSuccess(action: PageState<T>.() -> Unit): PageState<T> {
    result?.onSuccess { action() }
    return this
}

/**
 * 失败状态
 */
inline fun <T> PageState<T>.onFailure(action: PageState<T>.(exception: Throwable) -> Unit): PageState<T> {
    result?.onFailure { action(it) }
    return this
}

/**
 * 加载成功，并且总数据为空
 */
inline fun <T> PageState<T>.onViewSuccessEmpty(action: PageState<T>.() -> Unit): PageState<T> {
    onSuccess {
        if (data.isEmpty()) action()
    }
    return this
}

/**
 * 加载失败，并且总数据为空
 */
inline fun <T> PageState<T>.onViewFailureEmpty(action: PageState<T>.(exception: Throwable) -> Unit): PageState<T> {
    onFailure {
        if (data.isEmpty()) action(it)
    }
    return this
}

//-------------------- impl --------------------

private class PageLoaderImpl<T>(
    initial: List<T>,
    override val refreshPage: Int,
    private val dataHandler: suspend FPageLoader.LoadScope<T>.(page: Int, pageData: List<T>) -> List<T>?,
) : FPageLoader<T>, FPageLoader.LoadScope<T> {

    private val _refreshLoader = FLoader()
    private val _loadMoreLoader = FLoader()
    private val _state: MutableStateFlow<PageState<T>> = MutableStateFlow(PageState(data = initial))

    private var _currentPage = refreshPage - 1
    private val loadMorePage: Int
        get() = if (state.data.isEmpty()) refreshPage else _currentPage + 1

    override val state: PageState<T>
        get() = _state.value

    override val stateFlow: Flow<PageState<T>>
        get() = _state.asStateFlow()

    override val currentState: PageState<T>
        get() = _state.value

    override suspend fun refresh(
        notifyLoading: Boolean,
        onLoad: suspend FPageLoader.LoadScope<T>.(page: Int) -> List<T>,
    ): Result<List<T>> {
        return _refreshLoader.load(
            onStart = {
                // 刷新之前取消加载更多
                cancelLoadMore()
                if (notifyLoading) {
                    _state.update { it.copy(isRefreshing = true) }
                }
            },
            onFinish = {
                if (notifyLoading) {
                    _state.update { it.copy(isRefreshing = false) }
                }
            },
            onLoad = {
                val page = refreshPage
                onLoad(page).also { data ->
                    handleLoadSuccess(page, data)
                }
            },
            onFailure = { error ->
                handleLoadFailure(error)
            },
        )
    }

    override suspend fun loadMore(
        notifyLoading: Boolean,
        onLoad: suspend FPageLoader.LoadScope<T>.(page: Int) -> List<T>,
    ): Result<List<T>> {
        if (state.isRefreshing || state.isLoadingMore) {
            throw LoadMoreCancellationException()
        }

        return _loadMoreLoader.load(
            onStart = {
                if (notifyLoading) {
                    _state.update { it.copy(isLoadingMore = true) }
                }
            },
            onFinish = {
                if (notifyLoading) {
                    _state.update { it.copy(isLoadingMore = false) }
                }
            },
            onLoad = {
                val page = loadMorePage
                onLoad(page).also { data ->
                    handleLoadSuccess(page, data)
                }
            },
            onFailure = { error ->
                handleLoadFailure(error)
            },
        )
    }

    override suspend fun cancelRefresh() {
        _refreshLoader.cancelLoad()
    }

    override suspend fun cancelLoadMore() {
        _loadMoreLoader.cancelLoad()
    }

    override fun setData(data: List<T>) {
        _state.update {
            it.copy(data = data)
        }
    }

    private suspend fun handleLoadSuccess(page: Int, data: List<T>) {
        _currentPage = page
        _state.update {
            it.copy(
                data = dataHandler(page, data) ?: it.data,
                result = Result.success(Unit),
                page = page,
                pageSize = data.size,
            )
        }
    }

    private fun handleLoadFailure(e: Throwable) {
        _state.update {
            it.copy(result = Result.failure(e))
        }
    }
}

private class LoadMoreCancellationException : CancellationException("loadMore cancellation") {
    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyArray()
        return this
    }
}