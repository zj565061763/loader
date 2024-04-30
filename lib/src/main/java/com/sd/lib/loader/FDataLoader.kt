package com.sd.lib.loader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface FDataLoader<T> {

    /** 状态 */
    val state: DataState<T>

    /** 状态流 */
    val stateFlow: StateFlow<DataState<T>>

    /**
     * 开始加载，如果上一次加载还未完成，再次调用此方法，会取消上一次加载([CancellationException])
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

/**
 * 初始状态时触发[block]
 */
inline fun <T> DataState<T>.onInitial(block: DataState<T>.() -> Unit) {
    if (result == null) {
        block()
    }
}

/**
 * 成功状态时触发[block]
 */
inline fun <T> DataState<T>.onSuccess(block: DataState<T>.() -> Unit) {
    result?.onSuccess {
        block()
    }
}

/**
 * 成功状态时触发[block]
 */
inline fun <T> DataState<T>.onFailure(block: DataState<T>.(Throwable) -> Unit) {
    result?.onFailure {
        block(it)
    }
}

//-------------------- impl --------------------

private class DataLoaderImpl<T>(initial: T) : FDataLoader<T>, FDataLoader.LoadScope<T> {

    private val _loader = FLoader()
    private val _state = MutableStateFlow(DataState(data = initial))

    override val state: DataState<T>
        get() = _state.value

    override val stateFlow: StateFlow<DataState<T>>
        get() = _state.asStateFlow()

    override val currentState: DataState<T>
        get() = _state.value

    override suspend fun load(
        notifyLoading: Boolean,
        onLoad: suspend FDataLoader.LoadScope<T>.() -> T,
    ): Result<T> {
        return _loader.load(
            onFinish = {
                if (notifyLoading) {
                    _state.update { it.copy(isLoading = false) }
                }
            },
            onLoad = {
                if (notifyLoading) {
                    _state.update { it.copy(isLoading = true) }
                }
                try {
                    onLoad().also { data ->
                        currentCoroutineContext().ensureActive()
                        _state.update {
                            it.copy(
                                data = data,
                                result = Result.success(Unit),
                            )
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        // 取消异常
                    } else {
                        _state.update {
                            it.copy(result = Result.failure(e))
                        }
                    }
                    throw e
                }
            },
        )
    }

    override suspend fun cancelLoad() {
        _loader.cancelLoad()
    }
}