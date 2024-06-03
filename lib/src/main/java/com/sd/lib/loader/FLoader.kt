package com.sd.lib.loader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

interface FLoader {
    /**
     * 是否正在加载中
     */
    fun isLoading(): Boolean

    /**
     * 开始加载，如果上一次加载还未完成，再次调用此方法，会取消上一次加载([CancellationException])，
     * 如果[onLoad]触发了，则[onFinish]一定会触发，[onLoad]的异常会被捕获，除了[CancellationException]
     *
     * @param onFinish 结束回调
     * @param onLoad 加载回调
     */
    suspend fun <T> load(
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

//-------------------- impl --------------------

private class LoaderImpl : FLoader {

    private val _mutator = FMutator()

    override fun isLoading(): Boolean {
        return _mutator.isMutating()
    }

    override suspend fun <T> load(
        onFinish: () -> Unit,
        onLoad: suspend () -> T,
    ): Result<T> {
        return _mutator.mutate {
            try {
                onLoad().let { data ->
                    currentCoroutineContext().ensureActive()
                    Result.success(data)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Result.failure(e)
            } finally {
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

    fun isMutating(): Boolean = currentMutator.get() != null

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