package com.sd.lib.loader

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
  suspend fun <T> load(onLoad: suspend LoadScope.() -> T): Result<T>

  /** 如果正在加载中，会抛出[CancellationException] */
  suspend fun <T> tryLoad(onLoad: suspend LoadScope.() -> T): Result<T>

  /** 取消加载，并等待取消完成 */
  suspend fun cancel()

  data class State(
    /** 是否正在加载中 */
    val isLoading: Boolean = false,
  )

  interface LoadScope {
    /** 加载成功，加载失败，或者加载被取消，都会在最后触发[block] */
    fun onLoadFinish(block: () -> Unit)
  }
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
  private val _mutator = Mutator()
  private val _stateFlow = MutableStateFlow(FLoader.State())
  override val stateFlow: StateFlow<FLoader.State> = _stateFlow.asStateFlow()

  override fun isLoading(): Boolean {
    return _stateFlow.value.isLoading
  }

  override suspend fun <T> load(onLoad: suspend FLoader.LoadScope.() -> T): Result<T> {
    return _mutator.mutate {
      doLoad(onLoad)
    }
  }

  override suspend fun <T> tryLoad(onLoad: suspend FLoader.LoadScope.() -> T): Result<T> {
    return _mutator.mutateOrThrowCancellation {
      doLoad(onLoad)
    }
  }

  override suspend fun cancel() {
    _mutator.cancelMutate()
  }

  private suspend fun <T> doLoad(onLoad: suspend FLoader.LoadScope.() -> T): Result<T> {
    val loadScope = LoadScopeImpl()
    return try {
      _stateFlow.update { it.copy(isLoading = true) }
      with(loadScope) { onLoad() }.let { data ->
        currentCoroutineContext().ensureActive()
        Result.success(data)
      }
    } catch (e: Throwable) {
      if (e is CancellationException) throw e
      Result.failure(e)
    } finally {
      _stateFlow.update { it.copy(isLoading = false) }
      loadScope.notifyLoadFinish()
    }
  }

  private class LoadScopeImpl : FLoader.LoadScope {
    private var _onLoadFinishBlock: (() -> Unit)? = null

    override fun onLoadFinish(block: () -> Unit) {
      _onLoadFinishBlock = block
    }

    fun notifyLoadFinish() {
      _onLoadFinishBlock?.also { finishBlock ->
        _onLoadFinishBlock = null
        finishBlock()
      }
    }
  }
}

private class Mutator {
  private var _job: Job? = null
  private val _jobMutex = Mutex()
  private val _mutateMutex = Mutex()

  suspend fun <T> mutate(block: suspend () -> T): T {
    checkNested()
    return mutate(
      onStart = {},
      block = block,
    )
  }

  suspend fun <T> mutateOrThrowCancellation(block: suspend () -> T): T {
    checkNested()
    return mutate(
      onStart = { if (_job?.isActive == true) throw CancellationException() },
      block = block,
    )
  }

  suspend fun cancelMutate() {
    _jobMutex.withLock {
      _job?.cancelAndJoin()
      _job = null
    }
  }

  private suspend fun <T> mutate(
    onStart: () -> Unit,
    block: suspend () -> T,
  ): T {
    return coroutineScope {
      val mutateJob = coroutineContext[Job]!!

      _jobMutex.withLock {
        onStart()
        _job?.cancelAndJoin()
        _job = mutateJob
        mutateJob.invokeOnCompletion { releaseJob(mutateJob) }
      }

      doMutate(block)
    }
  }

  private suspend fun <T> doMutate(block: suspend () -> T): T {
    return _mutateMutex.withLock {
      withContext(MutateElement(mutator = this@Mutator)) {
        block()
      }
    }
  }

  private fun releaseJob(job: Job) {
    if (_jobMutex.tryLock()) {
      if (_job === job) _job = null
      _jobMutex.unlock()
    }
  }

  private suspend fun checkNested() {
    val element = currentCoroutineContext()[MutateElement]
    if (element?.mutator === this@Mutator) error("Nested invoke")
  }

  private class MutateElement(val mutator: Mutator) : AbstractCoroutineContextElement(MutateElement) {
    companion object Key : CoroutineContext.Key<MutateElement>
  }
}