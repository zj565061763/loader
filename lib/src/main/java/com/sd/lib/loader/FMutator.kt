package com.sd.lib.loader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll

internal class FMutator(
  /** 创建[cancelAndJoin]取消任务的异常 */
  private val newCancelCause: () -> CancellationException?,
  /** 创建替换任务时取消旧任务的异常 */
  private val newReplaceCause: () -> CancellationException?,
  /** 创建[mutateOrThrow]繁忙时抛出的异常 */
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

  suspend fun <T> mutate(block: suspend () -> T): T {
    _mutateMutex.checkNested()
    return coroutineScope {
      val mutateJob = coroutineContext[Job]!!
      mutateJob.clearOnCompletion()
      mutateJob.ensureActive()

      // 成为最新任务，并取消上一个任务
      synchronized(_lock) {
        (_job to _runningJob).also { _job = mutateJob }
      }.also { (prevJob, runningJob) ->
        prevJob?.cancel(newReplaceCause())
        runningJob?.join()
      }

      // 等待期间有更新的任务进入时放弃执行
      synchronized(_lock) {
        (_job !== mutateJob).also { if (!it) _runningJob = mutateJob }
      }.also { replaced ->
        if (replaced) mutateJob.cancel(newReplaceCause())
        mutateJob.ensureActive()
      }

      doMutate(block)
    }
  }

  suspend fun <T> mutateOrThrow(block: suspend () -> T): T {
    _mutateMutex.checkNested()
    return coroutineScope {
      val mutateJob = coroutineContext[Job]!!
      mutateJob.clearOnCompletion()
      mutateJob.ensureActive()

      synchronized(_lock) {
        // 等待中或清理中的任务都算忙，不取消也不等待
        if (_job?.isCompleted == false || _runningJob?.isCompleted == false) throw newBusyCause()
        _job = mutateJob
        _runningJob = mutateJob
      }

      doMutate(block)
    }
  }

  suspend fun cancelAndJoin() {
    _mutateMutex.checkNested()
    synchronized(_lock) {
      listOfNotNull(_job, _runningJob).distinct()
    }.also { jobs ->
      jobs.forEach { it.cancel(newCancelCause()) }
      jobs.joinAll()
    }
  }

  private fun Job.clearOnCompletion() {
    invokeOnCompletion {
      synchronized(_lock) {
        if (_job === this) _job = null
        if (_runningJob === this) _runningJob = null
      }
    }
  }

  private suspend fun <T> doMutate(block: suspend () -> T): T {
    return _mutateMutex.withLock {
      currentCoroutineContext().ensureActive()
      block()
    }
  }
}
