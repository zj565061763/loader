package com.sd.lib.loader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

interface FLoader {

   /** 状态流 */
   val stateFlow: Flow<LoaderState>

   /** 加载状态流 */
   val loadingFlow: Flow<Boolean>

   /** 状态 */
   val state: LoaderState

   /** 是否正在加载中 */
   val isLoading: Boolean

   /**
    * 开始加载，如果上一次加载还未完成，再次调用此方法，会取消上一次加载([CancellationException])，
    * 如果[onLoad]触发了，则[onFinish]一定会触发，[onLoad]的异常会被捕获，除了[CancellationException]
    *
    * @param notifyLoading 是否通知加载状态
    * @param onFinish 结束回调
    * @param onLoad 加载回调
    */
   suspend fun <T> load(
      notifyLoading: Boolean = true,
      onFinish: () -> Unit = {},
      onLoad: suspend () -> T,
   ): Result<T>

   /**
    * 取消加载
    */
   suspend fun cancelLoad()
}

/**
 * 创建[FLoader]
 */
fun FLoader(): FLoader = LoaderImpl()

//-------------------- state --------------------

data class LoaderState(
   /** 是否正在加载中 */
   val isLoading: Boolean = false,
)

//-------------------- impl --------------------

private class LoaderImpl : FLoader {
   private val _mutator = FMutator()
   private val _state = MutableStateFlow(LoaderState())

   override val stateFlow: Flow<LoaderState> = _state.asStateFlow()
   override val loadingFlow: Flow<Boolean> = stateFlow.map { it.isLoading }

   override val state: LoaderState get() = _state.value
   override val isLoading: Boolean get() = state.isLoading

   override suspend fun <T> load(
      notifyLoading: Boolean,
      onFinish: () -> Unit,
      onLoad: suspend () -> T,
   ): Result<T> {
      return _mutator.mutate {
         try {
            if (notifyLoading) {
               _state.update { it.copy(isLoading = true) }
            }
            onLoad().let { data ->
               currentCoroutineContext().ensureActive()
               Result.success(data)
            }
         } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Result.failure(e)
         } finally {
            if (notifyLoading) {
               _state.update { it.copy(isLoading = false) }
            }
            onFinish()
         }
      }
   }

   override suspend fun cancelLoad() {
      _mutator.cancelAndJoin()
   }
}

//-------------------- utils --------------------

private class FMutator {
   private class Mutator(val priority: Int, val job: Job) {
      fun canInterrupt(other: Mutator) = priority >= other.priority

      fun cancel() = job.cancel(MutationInterruptedException())
   }

   private val currentMutator = AtomicReference<Mutator?>(null)
   private val mutex = Mutex()

   private fun tryMutateOrCancel(mutator: Mutator) {
      while (true) {
         val oldMutator = currentMutator.get()
         if (oldMutator == null || mutator.canInterrupt(oldMutator)) {
            if (currentMutator.compareAndSet(oldMutator, mutator)) {
               oldMutator?.cancel()
               break
            }
         } else throw CancellationException("Current mutation had a higher priority")
      }
   }

   suspend fun <R> mutate(
      priority: Int = 0,
      block: suspend () -> R,
   ) = coroutineScope {
      val mutator = Mutator(priority, coroutineContext[Job]!!)

      tryMutateOrCancel(mutator)

      mutex.withLock {
         try {
            block()
         } finally {
            currentMutator.compareAndSet(mutator, null)
         }
      }
   }

   //-------------------- ext --------------------

   suspend fun cancelAndJoin() {
      while (true) {
         val mutator = currentMutator.get() ?: return
         mutator.cancel()
         try {
            mutator.job.join()
         } finally {
            currentMutator.compareAndSet(mutator, null)
         }
      }
   }
}

private class MutationInterruptedException : CancellationException("Mutation interrupted") {
   override fun fillInStackTrace(): Throwable {
      // Avoid null.clone() on Android <= 6.0 when accessing stackTrace
      stackTrace = emptyArray()
      return this
   }
}