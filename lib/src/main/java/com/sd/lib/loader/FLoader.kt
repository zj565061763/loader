package com.sd.lib.loader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

interface FLoader<T> {

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

    /**
     * 设置数据
     */
    fun setData(data: T)

    interface LoadScope<T> {
        /** 当前数据状态 */
        val currentState: DataState<T>
    }
}

/**
 * [FLoader]
 *
 * @param initial 初始值
 */
fun <T> FLoader(initial: T): FLoader<T> {
    return LoaderImpl(initial = initial)
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

private class LoaderImpl<T>(initial: T) : FLoader<T>, FLoader.LoadScope<T> {

    private val _mutator = FMutator()
    private val _state: MutableStateFlow<DataState<T>> = MutableStateFlow(DataState(data = initial))

    override val state: DataState<T>
        get() = _state.value

    override val stateFlow: Flow<DataState<T>>
        get() = _state.asStateFlow()

    override val dataFlow: Flow<T>
        get() = _state.map { it.data }.distinctUntilChanged()

    override val currentState: DataState<T>
        get() = this@LoaderImpl.state

    override suspend fun load(
        notifyLoading: Boolean,
        onLoad: suspend FLoader.LoadScope<T>.() -> T
    ): Result<T> {
        return _mutator.mutate {
            try {
                if (notifyLoading) {
                    _state.update { it.copy(isLoading = true) }
                }
                onLoad().let { data ->
                    _state.update {
                        it.copy(
                            data = data,
                            result = Result.success(Unit),
                        )
                    }
                    Result.success(data)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(result = Result.failure(e)) }
                Result.failure(e)
            } finally {
                if (notifyLoading) {
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    override suspend fun cancelLoad() {
        _mutator.mutate(999) { }
    }

    override fun setData(data: T) {
        _state.update { it.copy(data = data) }
    }
}