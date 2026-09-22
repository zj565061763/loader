package com.sd.lib.loader

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

internal class FMutator {
  private val _job = AtomicReference<Job?>()
  private val _jobMutex = Mutex()

  /** 串行执行用户 block，并提供同一实例的嵌套调用检测 */
  private val _mutateMutex = FMutex()

  suspend fun <T> mutate(block: suspend () -> T): T {
    _mutateMutex.checkNested()
    return mutate(
      lock = { _jobMutex.lock() },
      onStart = {},
      block = block,
    )
  }

  @Throws(BusyException::class)
  suspend fun <T> mutateOrThrow(block: suspend () -> T): T {
    _mutateMutex.checkNested()
    return mutate(
      // 锁被持有说明正在取消或替换任务，立即判定为忙，避免挂起等待旧任务清理
      lock = { if (!_jobMutex.tryLock()) throw BusyException() },
      onStart = { if (_job.get()?.isCompleted == false) throw BusyException() },
      block = block,
    )
  }

  suspend fun cancelAndJoin() {
    _mutateMutex.checkNested()
    _jobMutex.withLock {
      _job.get()?.cancelAndJoin()
      _job.set(null)
    }
  }

  private suspend fun <T> mutate(
    lock: suspend () -> Unit,
    onStart: () -> Unit,
    block: suspend () -> T,
  ): T {
    return coroutineScope {
      val mutateJob = coroutineContext[Job]!!

      mutateJob.ensureActive()
      lock()

      try {
        mutateJob.ensureActive()
        onStart()
        _job.get()?.cancelAndJoin()
        _job.set(mutateJob)
        mutateJob.invokeOnCompletion { _job.compareAndSet(mutateJob, null) }
      } finally {
        _jobMutex.unlock()
      }

      doMutate(block)
    }
  }

  private suspend fun <T> doMutate(block: suspend () -> T): T {
    return _mutateMutex.withLock {
      currentCoroutineContext().ensureActive()
      block()
    }
  }

  class BusyException : Exception()
}
