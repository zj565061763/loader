package com.sd.lib.loader

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

/** 协调加载任务，支持取消旧任务或在繁忙时拒绝新任务 */
interface FLoader {
  /**
   * 是否繁忙的状态流，取值含义同[isBusy]。
   * 快速变化的中间值可能被合并。
   * 多线程下它的值可能略晚于[isBusy]：收到 false 时其他线程可能已经开始新的加载。
   * 只是状态通知，不能用来先判断再调用[tryLoad]。
   */
  val isBusyFlow: StateFlow<Boolean>

  /**
   * 是否繁忙，即是否有尚未结束的加载，等待上一次加载结束的和取消后仍在清理的也算。
   * 等待期间被新的[load]替换的加载不算：返回 false 时，它的[load]可能还没抛出[ReplacedCancellationException]。
   * 繁忙时[tryLoad]会抛出[BusyCancellationException]。
   * 只是调用时刻的快照，不能用来先判断再调用[tryLoad]。
   */
  fun isBusy(): Boolean

  /**
   * 开始加载：先取消上一次加载并等待它结束，再执行[onLoad]。
   *
   * [onLoad]的返回值包装为[Result.success]，普通异常包装为[Result.failure]，[CancellationException]原样抛出。
   * 加载会等[onLoad]内用当前协程上下文启动的子协程结束，子协程的普通异常同样包装为[Result.failure]。
   *
   * 加载被取消时抛出取消异常，不返回[Result]，[onLoad]内收到的也是同一类型：
   * 被[cancelAndJoin]取消时是[ManualCancellationException]，
   * 被新的[load]替换时是[ReplacedCancellationException]，
   * 被调用方取消时是调用方的取消原因。
   * 多次取消时只保留最先的原因，例如先被替换再被[cancelAndJoin]取消，抛出的仍是[ReplacedCancellationException]。
   * 在[NonCancellable]中调用时只是不会被调用方取消，仍会被[cancelAndJoin]取消或被新的[load]替换。
   * 多个 Loader 嵌套使用时，[ManualCancellationException]和[ReplacedCancellationException]也可能来自外层或内层的 Loader，不代表当前 Loader 被取消。
   *
   * [onLoad]内不能调用同一个 Loader 的[load]、[tryLoad]或[cancelAndJoin]，
   * 否则抛出[IllegalStateException]，不捕获的话会包装为[Result.failure]。
   * 嵌套检测依赖协程上下文，通过[runBlocking]、新线程或新的根协程作用域调用时检测不到，可能死锁。
   * 在[onLoad]内启动的协程如果继承了上下文但换成独立`Job`，加载结束后调用这些方法仍会抛出[IllegalStateException]。
   *
   * 在`flow {}`中不能在[onLoad]内调用`emit`，否则返回[Result.failure]。
   * 请改用[channelFlow]，或在[onLoad]外`emit`。
   *
   * @param onLoad 加载回调
   */
  suspend fun <T> load(onLoad: suspend () -> T): Result<T>

  /**
   * 开始加载，但不取消也不等待上一次加载：繁忙时立即抛出[BusyCancellationException]，繁忙的含义见[isBusy]。
   * 其余行为与[load]相同。
   *
   * [BusyCancellationException]是[CancellationException]的子类，不捕获会取消调用方协程。
   * 在其他 Loader 的加载回调中调用时，外层加载也会抛出它，不返回[Result.failure]。
   * 在加载回调内启动的子协程中调用时，它只取消该子协程，不影响外层加载。
   */
  suspend fun <T> tryLoad(onLoad: suspend () -> T): Result<T>

  /**
   * 取消加载，并等待取消完成。
   * 调用方已取消时仍会发起取消，但不保证等待完成，可能抛出[CancellationException]；
   * 在调用方的`finally`中需要等待完成时，请使用`withContext(NonCancellable)`。
   */
  suspend fun cancelAndJoin()

  /** 加载被[cancelAndJoin]取消时抛出的取消异常 */
  class ManualCancellationException : CancellationException("Cancelled by cancelAndJoin")

  /** 加载被新的[load]替换时抛出的取消异常 */
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

  override val isBusyFlow: StateFlow<Boolean>
    get() = _mutator.isBusyFlow

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
      // 等待 onLoad 用当前上下文启动的子协程结束，让其异常也包装为 Result.failure
      Result.success(coroutineScope { onLoad() })
    } catch (e: Throwable) {
      if (e is CancellationException) throw e
      Result.failure(e)
    }
  }
}
