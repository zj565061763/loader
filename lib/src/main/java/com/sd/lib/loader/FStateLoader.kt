package com.sd.lib.loader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

interface FStateLoader<T> {

    /** 状态 */
    val state: DataState<T>

    /** 状态流 */
    val stateFlow: Flow<DataState<T>>

    /** 数据流 */
    val dataFlow: Flow<T>

    /**
     * 加载数据，如果上一次加载还未完成，再次调用此方法，会取消上一次加载
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
        /** 当前数据状态 */
        val currentState: DataState<T>
    }
}

/**
 * [FStateLoader]
 *
 * @param initial 初始值
 */
fun <T> FStateLoader(initial: T): FStateLoader<T> {
    return StateLoaderImpl(initial = initial)
}

//-------------------- state --------------------

data class DataState<T>(
    /** 数据 */
    val data: T,

    /** 数据结果 */
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

/**
 * 初始状态
 */
inline fun <T> DataState<T>.onInitial(action: DataState<T>.() -> Unit): DataState<T> {
    if (result == null) action()
    return this
}

/**
 * 成功状态
 */
inline fun <T> DataState<T>.onSuccess(action: DataState<T>.() -> Unit): DataState<T> {
    result?.onSuccess { action() }
    return this
}

/**
 * 失败状态
 */
inline fun <T> DataState<T>.onFailure(action: DataState<T>.(exception: Throwable) -> Unit): DataState<T> {
    result?.onFailure { action(it) }
    return this
}

//-------------------- impl --------------------

private class StateLoaderImpl<T>(initial: T) : FStateLoader<T>, FStateLoader.LoadScope<T> {

    private val _loader = FLoader()
    private val _state: MutableStateFlow<DataState<T>> = MutableStateFlow(DataState(data = initial))

    override val state: DataState<T>
        get() = _state.value

    override val stateFlow: Flow<DataState<T>>
        get() = _state.asStateFlow()

    override val dataFlow: Flow<T>
        get() = _state.map { it.data }.distinctUntilChanged()

    override val currentState: DataState<T>
        get() = this@StateLoaderImpl.state

    override suspend fun load(
        notifyLoading: Boolean,
        onLoad: suspend FStateLoader.LoadScope<T>.() -> T
    ): Result<T> {
        return _loader.load(
            onStart = {
                if (notifyLoading) {
                    _state.update { it.copy(isLoading = true) }
                }
            },
            onFinish = {
                if (notifyLoading) {
                    _state.update { it.copy(isLoading = false) }
                }
            },
            onLoad = {
                onLoad()
            },
            onSuccess = { data ->
                _state.update {
                    it.copy(
                        data = data,
                        result = Result.success(Unit),
                    )
                }
            },
            onError = { error ->
                _state.update {
                    it.copy(result = Result.failure(error))
                }
            },
        )
    }

    override suspend fun cancelLoad() {
        _loader.cancelLoad()
    }
}