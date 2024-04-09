package com.sd.lib.loader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        /** 当前状态 */
        val currentState: PageState<T>

        /** 刷新数据的页码，例如数据源页码从1开始，那么[refreshPage]就为1 */
        val refreshPage: Int
    }
}

/**
 * [FPageLoader]
 *
 * @param initial 初始值
 * @param refreshPage 刷新数据的页码，例如数据源页码从1开始，那么[refreshPage]就为1
 * @param dataHandler 处理每页的数据，并返回总的数据，返回null则总数据不变
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

/** 是否成功状态(最后一次加载的结果) */
val PageState<*>.isSuccess: Boolean get() = result?.isSuccess == true

/** 是否失败状态(最后一次加载的结果) */
val PageState<*>.isFailure: Boolean get() = result?.isFailure == true

/** 是否显示没有更多数据 */
val PageState<*>.showNoMoreData: Boolean get() = data.isNotEmpty() && pageSize == 0

/** 是否显示加载数据为空 */
val PageState<*>.showLoadEmpty: Boolean get() = isSuccess && data.isEmpty()

/** 是否显示加载数据失败 */
val PageState<*>.showLoadFailure: Boolean get() = isFailure && data.isEmpty()

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
            onFinish = {
                if (notifyLoading) {
                    _state.update { it.copy(isRefreshing = false) }
                }
            },
            onLoad = {
                // 取消加载更多
                cancelLoadMore()
                if (notifyLoading) {
                    _state.update { it.copy(isRefreshing = true) }
                }

                val page = refreshPage

                try {
                    onLoad(page).also { data ->
                        handleLoadSuccess(page, data)
                    }
                } catch (e: Throwable) {
                    if (e !is CancellationException) {
                        _state.update {
                            it.copy(result = Result.failure(e))
                        }
                    }
                    throw e
                }
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
            onFinish = {
                if (notifyLoading) {
                    _state.update { it.copy(isLoadingMore = false) }
                }
            },
            onLoad = {
                if (notifyLoading) {
                    _state.update { it.copy(isLoadingMore = true) }
                }

                val page = loadMorePage

                try {
                    onLoad(page).also { data ->
                        handleLoadSuccess(page, data)
                    }
                } catch (e: Throwable) {
                    if (e !is CancellationException) {
                        _state.update {
                            it.copy(result = Result.failure(e))
                        }
                    }
                    throw e
                }
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
        // dataHandler可能会抛异常，发生异常后不执行下面的代码
        currentCoroutineContext().ensureActive()
        val totalData = dataHandler(page, data)
        currentCoroutineContext().ensureActive()

        if (page == refreshPage) {
            // refresh
            _currentPage = refreshPage
        } else {
            // loadMore
            if (data.isNotEmpty()) {
                _currentPage = page
            }
        }

        _state.update {
            it.copy(
                data = totalData ?: it.data,
                result = Result.success(Unit),
                page = page,
                pageSize = data.size,
            )
        }
    }
}

private class LoadMoreCancellationException : CancellationException("loadMore cancellation") {
    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyArray()
        return this
    }
}