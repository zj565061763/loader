package com.sd.demo.loader

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class LoaderResultState {
    Initial,
    Success,
    Failure,
}

class TestContinuation {
    private var _result: Unit? = null
    private val _holder: MutableSet<CancellableContinuation<Unit>> = mutableSetOf()

    suspend fun await() = suspendCancellableCoroutine { cont ->
        synchronized(this@TestContinuation) {
            if (_result == null) {
                _holder.add(cont)
                cont.invokeOnCancellation {
                    synchronized(this@TestContinuation) {
                        _holder.remove(cont)
                    }
                }
            } else {
                cont.resume(Unit)
            }
        }
    }

    fun resume() {
        synchronized(this@TestContinuation) {
            _result = Unit
            while (_holder.isNotEmpty()) {
                _holder.toTypedArray().forEach { cont ->
                    _holder.remove(cont)
                    cont.resume(Unit)
                }
            }
        }
    }
}