package com.sd.lib.loader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 串行执行任务，新任务可以取消并等待旧任务，也可以在繁忙时被拒绝 */
internal class FMutator(
  /** [cancelAndJoin]取消任务时使用的取消原因 */
  private val newCancelCause: () -> CancellationException,
  /** 新任务替换旧任务时使用的取消原因 */
  private val newReplaceCause: () -> CancellationException,
  /** [mutateOrThrow]繁忙时抛出的异常 */
  private val newBusyCause: () -> CancellationException,
) {
  /** 保护[_job]和[_runningJob]，锁内不能挂起 */
  private val _lock = Any()

  /** 最近进入的任务，可能还在等待[_runningJob]结束 */
  private var _job: Job? = null

  /** 已开始执行 block 的任务，新任务要等它结束才能执行 */
  private var _runningJob: Job? = null

  /** 串行执行用户 block，并提供同一实例的嵌套调用检测 */
  private val _mutateMutex = FMutex()

  private val _isBusyFlow = MutableStateFlow(false)

  /** [isBusy]的状态流，快速变化的中间值可能被合并 */
  val isBusyFlow: StateFlow<Boolean> = _isBusyFlow.asStateFlow()

  /** 取消上一个任务并等待它结束，再执行[block] */
  suspend fun <T> mutate(block: suspend () -> T): T {
    _mutateMutex.checkNested()
    return coroutineScope {
      val mutateJob = coroutineContext[Job]!!
      mutateJob.clearOnCompletion()
      mutateJob.ensureActive()

      // 成为最新任务，并取消上一个任务
      val (prevJob, runningJob) = synchronized(_lock) {
        (_job to _runningJob).also { _job = mutateJob }
      }
      prevJob?.cancel(newReplaceCause())
      syncBusyFlow()
      // 等待期间不能再引用 prevJob，否则它结束后仍被持有
      runningJob?.join()

      // 仍是最新任务才登记为运行任务，否则已被更新的任务顶替，取消自己
      synchronized(_lock) {
        (_job !== mutateJob).also { if (!it) _runningJob = mutateJob }
      }.also { replaced ->
        if (replaced) mutateJob.cancel(newReplaceCause())
      }

      // 被顶替时 doMutate 会检查到取消，不执行 block
      doMutate(block)
    }
  }

  /** 不繁忙时执行[block]，繁忙时抛出异常，不取消也不等待已有任务 */
  suspend fun <T> mutateOrThrow(block: suspend () -> T): T {
    _mutateMutex.checkNested()
    return coroutineScope {
      val mutateJob = coroutineContext[Job]!!
      mutateJob.clearOnCompletion()
      mutateJob.ensureActive()

      // 判忙和登记必须在同一次加锁中完成
      synchronized(_lock) {
        if (isBusy()) throw newBusyCause()
        _job = mutateJob
        _runningJob = mutateJob
      }
      syncBusyFlow()

      doMutate(block)
    }
  }

  /** 取消调用时已进入的任务，并等待它们结束 */
  suspend fun cancelAndJoin() {
    _mutateMutex.checkNested()

    val (job, runningJob) = synchronized(_lock) {
      _job to _runningJob.takeIf { it !== _job }
    }
    job?.cancel(newCancelCause())
    runningJob?.cancel(newCancelCause())

    // 逐个等待，等 runningJob 时不能再引用 job，否则它结束后仍被持有
    job?.join()
    runningJob?.join()
  }

  /** 是否有尚未结束的任务，等待中或清理中的也算 */
  fun isBusy(): Boolean {
    return synchronized(_lock) {
      _job?.isCompleted == false || _runningJob?.isCompleted == false
    }
  }

  private fun Job.clearOnCompletion() {
    invokeOnCompletion {
      synchronized(_lock) {
        if (_job === this) _job = null
        if (_runningJob === this) _runningJob = null
      }
      syncBusyFlow()
    }
  }

  /** 把[isBusy]同步到[isBusyFlow]，必须在锁外调用，因为收集者可能在赋值时内联执行 */
  private fun syncBusyFlow() {
    do {
      val busy = isBusy()
      _isBusyFlow.value = busy
      // 赋值期间登记变了的话，刚赋的值可能已经过期，重新同步
    } while (isBusy() != busy)
  }

  private suspend fun <T> doMutate(block: suspend () -> T): T {
    return _mutateMutex.withLock {
      // 进入锁时切换上下文期间可能被取消，执行 block 前再检查
      currentCoroutineContext().ensureActive()
      block()
    }
  }
}
