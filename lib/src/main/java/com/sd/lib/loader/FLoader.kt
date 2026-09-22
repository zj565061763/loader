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
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

/** 协调加载任务，支持取消旧任务或在繁忙时拒绝新任务 */
interface FLoader {
  /** 状态流 */
  val stateFlow: StateFlow<State>

  /**
   * 是否正在加载中，仅用于展示状态。
   * 新旧任务切换时可能短暂为 false，不能用来判断[tryLoad]是否会成功。
   */
  fun isLoading(): Boolean

  /**
   * 开始加载，并取消和等待上一次加载结束。
   *
   * 被新调用取消的[load]会抛出[CancellationException]，不会返回[Result]。
   * [onLoad]抛出的普通异常会包装为[Result.failure]，[CancellationException]会原样抛出。
   *
   * [onLoad]中不允许嵌套调用[load]、[tryLoad]或[cancelAndJoin]，否则会抛出异常。
   * 嵌套检测依赖协程上下文，通过[runBlocking]或新线程绕开原上下文时无法检测，可能导致死锁。
   *
   * @param onLoad 加载回调
   */
  suspend fun <T> load(onLoad: suspend () -> T): Result<T>

  /**
   * 功能与[load]相同，但加载繁忙时立即抛出[BusyCancellationException]。
   *
   * [BusyCancellationException]是[CancellationException]的子类，不捕获会取消调用方协程。
   * 在加载回调中调用其他 Loader 的[tryLoad]时，后者抛出的忙异常也会向外传播。
   */
  suspend fun <T> tryLoad(onLoad: suspend () -> T): Result<T>

  /** 取消加载，并等待取消完成 */
  suspend fun cancelAndJoin()

  /** 加载状态 */
  data class State(
    /** 是否正在加载中 */
    val isLoading: Boolean = false,
  )

  /** [tryLoad]在加载繁忙时抛出的取消异常 */
  class BusyCancellationException : CancellationException("Loader is busy")
}

/** 创建一个[FLoader] */
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
