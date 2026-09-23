package com.sd.lib.loader

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal class FMutator {
  private val _job = AtomicReference<Job?>()
  private val _jobMutex = Mutex()

  /** 串行执行用户 block，并提供同一实例的嵌套调用检测 */
  private val _mutateMutex = FMutex()

  /** 已进入但尚未设置为[_job]的任务，供[cancelAndJoin]一并取消 */
  private val _preparingJobs = Collections.newSetFromMap<Job>(ConcurrentHashMap())

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
    // 先读 _preparingJobs 再读 _job，与 mutate 先设置 _job 再移除的顺序配对
    val jobs = _preparingJobs.toMutableList()
    _job.get()?.also { jobs.add(it) }
    // 先全部取消再等待，调用方已取消时也不会漏掉
    jobs.forEach { it.cancel() }
    jobs.joinAll()
  }

  private suspend fun <T> mutate(
    lock: suspend () -> Unit,
    onStart: () -> Unit,
    block: suspend () -> T,
  ): T {
    return coroutineScope {
      val mutateJob = coroutineContext[Job]!!

      _preparingJobs.add(mutateJob)
      mutateJob.invokeOnCompletion {
        _preparingJobs.remove(mutateJob)
        _job.compareAndSet(mutateJob, null)
      }

      mutateJob.ensureActive()
      lock()

      try {
        mutateJob.ensureActive()
        onStart()
        _job.get()?.cancelAndJoin()
        _job.set(mutateJob)
        // 必须先设置 _job 再移除，与 cancelAndJoin 的读取顺序配对
        _preparingJobs.remove(mutateJob)
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
