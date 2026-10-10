package com.sd.lib.loader

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** 协调加载任务，支持取消旧任务或在繁忙时拒绝新任务 */
interface FLoader {
  /**
   * 加载状态流，仅用于展示状态，快速变化的中间值可能被合并。
   * 新旧任务切换时可能短暂为 false，不能用来判断[tryLoad]是否会成功，是否繁忙以[isBusy]为准。
   * 变为 false 时加载尚未结束，此时调用[load]或[cancelAndJoin]仍会取消它并丢弃加载结果；
   * 需要接着加载时，请在[load]返回后再调用。
   */
  val loadingFlow: StateFlow<Boolean>

  /**
   * 是否繁忙，即是否有尚未结束的加载，等待中或取消后仍在清理的也算。
   * 繁忙时[tryLoad]会抛出[BusyCancellationException]。
   * 只是调用时刻的快照，不能用来先判断再调用[tryLoad]。
   */
  fun isBusy(): Boolean

  /**
   * 开始加载，并取消和等待上一次加载结束。
   *
   * 被[cancelAndJoin]取消时抛出[ManualCancellationException]，
   * 被新的[load]取消时抛出[ReplacedCancellationException]，
   * 被调用方取消时抛出调用方的取消原因，都不会返回[Result]。
   * 例如外层 Loader 被[cancelAndJoin]取消时，在其加载回调中调用的[load]抛出的也是[ManualCancellationException]。
   * 加载被多次取消时只保留最先的取消原因，例如先被新的[load]替换再被[cancelAndJoin]取消时抛出的仍是[ReplacedCancellationException]。
   * 在[NonCancellable]中调用时只是不会被调用方取消，仍会被[cancelAndJoin]或新的[load]取消。
   *
   * [onLoad]内收到的取消异常也是这些类型。
   * [onLoad]抛出的普通异常会包装为[Result.failure]，[CancellationException]会原样抛出。
   * [onLoad]内用当前协程上下文启动的子协程结束后加载才结束，子协程的普通异常同样包装为[Result.failure]。
   * [withTimeout]超时的异常也会原样抛出，需要[Result.failure]时请改用[withTimeoutOrNull]或转换为普通异常。
   * 在[onLoad]中调用其他 Loader 时，它被别处取消抛出的[ManualCancellationException]或[ReplacedCancellationException]也会原样抛出，不代表当前 Loader 被取消。
   *
   * [onLoad]中不允许嵌套调用[load]、[tryLoad]或[cancelAndJoin]，未捕获的嵌套异常会包装为[Result.failure]。
   * 嵌套检测依赖协程上下文，通过[runBlocking]、新线程或新的根协程作用域绕开原上下文时无法检测，可能导致死锁。
   * 在[onLoad]内启动的协程如果继承了上下文但换成独立`Job`，即使加载已结束，调用这些方法也会被判为嵌套，直接抛出[IllegalStateException]。
   *
   * 在`flow {}`中不能在[onLoad]内调用`emit`，否则返回[Result.failure]。
   * 请改用[channelFlow]，或在[onLoad]外`emit`。
   *
   * @param onLoad 加载回调
   */
  suspend fun <T> load(onLoad: suspend () -> T): Result<T>

  /**
   * 开始加载，但不取消也不等待上一次加载：加载繁忙时立即抛出[BusyCancellationException]，上一次加载取消后仍在清理时也算繁忙。
   * 其余行为与[load]相同。
   *
   * [BusyCancellationException]是[CancellationException]的子类，不捕获会取消调用方协程。
   * 在加载回调中调用其他 Loader 的[tryLoad]时，后者抛出的忙异常也会向外传播。
   * 在回调内启动的子协程中调用时，忙异常按子协程取消处理，不会传播到当前加载。
   */
  suspend fun <T> tryLoad(onLoad: suspend () -> T): Result<T>

  /**
   * 取消加载，并等待取消完成。
   * 调用方已取消时仍会发起取消，但不保证等待完成，可能抛出[CancellationException]；
   * 需要在外部`finally`中等待完成时，请使用`withContext(NonCancellable)`。
   */
  suspend fun cancelAndJoin()

  /** 加载被[cancelAndJoin]取消时抛出的取消异常 */
  class ManualCancellationException : CancellationException("Cancelled by cancelAndJoin")

  /** 加载被新的[load]取消时抛出的取消异常 */
  class ReplacedCancellationException : CancellationException("Cancelled by new load")

  /** [tryLoad]在加载繁忙时抛出的取消异常 */
  class BusyCancellationException : CancellationException("Loader is busy")
}

/** 创建一个[FLoader] */
fun FLoader(): FLoader = LoaderImpl()

/** 对[runCatching]的包装，如果是取消异常则抛出 */
inline fun <R> safeRunCatching(block: () -> R): Result<R> {
  return runCatching(block)
    .onFailure { if (it is CancellationException) throw it }
}

//-------------------- impl --------------------

private class LoaderImpl : FLoader {
  private val _mutator = FMutator(
    newCancelCause = { FLoader.ManualCancellationException() },
    newReplaceCause = { FLoader.ReplacedCancellationException() },
    newBusyCause = { FLoader.BusyCancellationException() },
  )
  private val _loadingFlow = MutableStateFlow(false)
  override val loadingFlow: StateFlow<Boolean> = _loadingFlow.asStateFlow()

  override fun isBusy(): Boolean {
    return _mutator.isBusy()
  }

  override suspend fun <T> load(onLoad: suspend () -> T): Result<T> {
    return _mutator.mutate {
      doLoad(onLoad)
    }
  }

  override suspend fun <T> tryLoad(onLoad: suspend () -> T): Result<T> {
    return _mutator.mutateOrThrow {
      doLoad(onLoad)
    }
  }

  override suspend fun cancelAndJoin() {
    _mutator.cancelAndJoin()
  }

  private suspend fun <T> doLoad(onLoad: suspend () -> T): Result<T> {
    return try {
      _loadingFlow.value = true
      // 等待 onLoad 用当前上下文启动的子协程结束，让其异常也包装为 Result.failure
      Result.success(coroutineScope { onLoad() })
    } catch (e: Throwable) {
      if (e is CancellationException) throw e
      Result.failure(e)
    } finally {
      _loadingFlow.value = false
    }
  }
}
