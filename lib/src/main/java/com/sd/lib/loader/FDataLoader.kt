package com.sd.lib.loader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

interface FDataLoader<T> {

    /** 状态 */
    val state: DataState<T>

    /** 状态流 */
    val stateFlow: Flow<DataState<T>>

    /** 数据流 */
    val dataFlow: Flow<T>

    /**
     * 开始加载，如果上一次加载还未完成，再次调用此方法，会取消上一次加载
     *
     * @param notifyLoading 是否通知[DataState.isLoading]
     * @param onLoad 加载回调
     */
    suspend fun load(
        notifyLoading: Boolean = true,
        onLoad: suspend LoadScope<T>.() -> T,
    ): Result<T>

    /**
     * 取消加载
     */
    suspend fun cancelLoad()

    interface LoadScope<T> {
        /** 当前状态 */
        val currentState: DataState<T>
    }
}

/**
 * [FDataLoader]
 *
 * @param initial 初始值
 */
fun <T> FDataLoader(initial: T): FDataLoader<T> {
    return DataLoaderImpl(initial = initial)
}

//-------------------- state --------------------

data class DataState<T>(
    /** 数据 */
    val data: T,

    /** 最后一次加载的结果 */
    val result: Result<Unit>? = null,

    /** 是否正在加载中 */
    val isLoading: Boolean = false,
)

/** 是否初始状态 */
val DataState<*>.isInitial: Boolean get() = result == null

/** 是否成功状态 */
val DataState<*>.isSuccess: Boolean get() = result?.isSuccess == true

/** 是否失败状态 */
val DataState<*>.isFailure: Boolean get() = result?.isFailure == true

//-------------------- impl --------------------

private class DataLoaderImpl<T>(initial: T) : FDataLoader<T>, FDataLoader.LoadScope<T> {

    private val _loader = FLoader()
    private val _state: MutableStateFlow<DataState<T>> = MutableStateFlow(DataState(data = initial))

    override val state: DataState<T>
        get() = _state.value

    override val stateFlow: Flow<DataState<T>>
        get() = _state.asStateFlow()

    override val dataFlow: Flow<T>
        get() = _state.map { it.data }.distinctUntilChanged()

    override val currentState: DataState<T>
        get() = _state.value

    override suspend fun load(
        notifyLoading: Boolean,
        onLoad: suspend FDataLoader.LoadScope<T>.() -> T
    ): Result<T> {
        return _loader.load(
            onFinish = {
                if (notifyLoading) {
                    _state.update { it.copy(isLoading = false) }
                }
            },
            onFailure = { error ->
                _state.update {
                    it.copy(result = Result.failure(error))
                }
            },
            onLoad = {
                if (notifyLoading) {
                    _state.update { it.copy(isLoading = true) }
                }
                onLoad().also { data ->
                    _state.update {
                        it.copy(
                            data = data,
                            result = Result.success(Unit),
                        )
                    }
                }
            },
        )
    }

    override suspend fun cancelLoad() {
        _loader.cancelLoad()
    }
}