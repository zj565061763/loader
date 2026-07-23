package com.sd.lib.loader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

interface FLoader {
  /** 状态流 */
  val stateFlow: StateFlow<State>

  /** 是否正在加载中 */
  fun isLoading(): Boolean

  /**
   * 开始加载，如果上一次加载还未完成，再次调用此方法，会取消上一次加载，
   * [onLoad]的异常会被捕获，除了[CancellationException]
   *
   * 注意：[onLoad]中不允许嵌套调用[load]，否则会抛异常
   *
   * @param onLoad 加载回调
   */
  suspend fun <T> load(onLoad: suspend () -> T): Result<T>

  /** 如果正在加载中，则抛出[BusyCancellationException]，该异常是[CancellationException]，不捕获会静默取消调用协程 */
  suspend fun <T> tryLoad(onLoad: suspend () -> T): Result<T>

  /** 取消加载，并等待取消完成 */
  suspend fun cancelAndJoin()

  data class State(
    /** 是否正在加载中 */
    val isLoading: Boolean = false,
  )

  /** [FLoader.tryLoad] */
  class BusyCancellationException : CancellationException()
}

fun FLoader(): FLoader = LoaderImpl()

/** 加载状态流 */
val FLoader.loadingFlow: Flow<Boolean>
  get() = stateFlow.map { it.isLoading }.distinctUntilChanged()

/** 对[runCatching]的包装，如果是取消异常则抛出 */
inline fun <R> safeRunCatching(block: () -> R): Result<R> {
  return runCatching(block)
    .onFailure { if (it is CancellationException) throw it }
}

//-------------------- impl --------------------

private class LoaderImpl : FLoader {
  private val _mutator = FMutator()
  private val _stateFlow = MutableStateFlow(FLoader.State())
  override val stateFlow: StateFlow<FLoader.State> = _stateFlow.asStateFlow()

  override fun isLoading(): Boolean {
    return _stateFlow.value.isLoading
  }

  override suspend fun <T> load(onLoad: suspend () -> T): Result<T> {
    return _mutator.mutate {
      doLoad(onLoad)
    }
  }

  override suspend fun <T> tryLoad(onLoad: suspend () -> T): Result<T> {
    return try {
      _mutator.mutateOrThrow {
        doLoad(onLoad)
      }
    } catch (_: FMutator.BusyException) {
      throw FLoader.BusyCancellationException()
    }
  }

  override suspend fun cancelAndJoin() {
    _mutator.cancelAndJoin()
  }

  private suspend fun <T> doLoad(onLoad: suspend () -> T): Result<T> {
    return try {
      _stateFlow.update { it.copy(isLoading = true) }
      onLoad().let { data ->
        currentCoroutineContext().ensureActive()
        Result.success(data)
      }
    } catch (e: Throwable) {
      if (e is CancellationException) throw e
      Result.failure(e)
    } finally {
      _stateFlow.update { it.copy(isLoading = false) }
    }
  }
}
