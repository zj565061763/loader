package com.sd.lib.loader

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FMutator {
  @Volatile
  private var _job: Job? = null
  private val _jobMutex = Mutex()
  private val _mutateMutex = FMutex()

  suspend fun <T> mutate(block: suspend () -> T): T {
    _mutateMutex.checkNested()
    return mutate(
      onStart = {},
      block = block,
    )
  }

  @Throws(BusyException::class)
  suspend fun <T> mutateOrThrow(block: suspend () -> T): T {
    _mutateMutex.checkNested()
    return mutate(
      onStart = { if (_job?.isCompleted == false) throw BusyException() },
      block = block,
    )
  }

  suspend fun cancelAndJoin() {
    _mutateMutex.checkNested()
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
        mutateJob.invokeOnCompletion {
          if (_jobMutex.tryLock()) {
            if (_job === mutateJob) _job = null
            _jobMutex.unlock()
          }
        }
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
