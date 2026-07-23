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
  suspend fun <T> load(onLoad: suspend () -> T): Result<T>

  /** 如果正在加载中，则返回null */
  suspend fun <T> tryLoad(onLoad: suspend () -> T): Result<T>?

  /** 取消加载，并等待取消完成 */
  suspend fun cancel()

  data class State(
    /** 是否正在加载中 */
    val isLoading: Boolean = false,
  )
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

  override suspend fun <T> load(onLoad: suspend () -> T): Result<T> {
    return _mutator.mutate {
      doLoad(onLoad)
    }
  }

  override suspend fun <T> tryLoad(onLoad: suspend () -> T): Result<T>? {
    return try {
      _mutator.mutateOrThrow {
        doLoad(onLoad)
      }
    } catch (_: Mutator.BusyException) {
      null
    }
  }

  override suspend fun cancel() {
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

  @Throws(BusyException::class)
  suspend fun <T> mutateOrThrow(block: suspend () -> T): T {
    checkNested()
    return mutate(
      onStart = { if (_job?.isActive == true) throw BusyException() },
      block = block,
    )
  }

  suspend fun cancelAndJoin() {
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
        mutateJob.invokeOnCompletion { tryLockJobMutex { if (_job === mutateJob) _job = null } }
      }

      doMutate(block)
    }
  }

  private suspend fun <T> doMutate(block: suspend () -> T): T {
    return _mutateMutex.withLock {
      withContext(MutateElement(_mutateKey)) {
        currentCoroutineContext().ensureActive()
        block()
      }
    }
  }

  private inline fun tryLockJobMutex(block: () -> Unit) {
    if (_jobMutex.tryLock()) {
      try {
        block()
      } finally {
        _jobMutex.unlock()
      }
    }
  }

  private suspend fun checkNested() {
    if (currentCoroutineContext()[_mutateKey] != null) error("Nested invoke")
  }

  private val _mutateKey = object : CoroutineContext.Key<MutateElement> {}

  private class MutateElement(key: CoroutineContext.Key<MutateElement>) : AbstractCoroutineContextElement(key)

  class BusyException : Exception()
}