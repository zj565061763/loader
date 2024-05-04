package com.sd.demo.loader

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class TestContinuation {
    private var _result: Unit? = null
    private val _holder: MutableSet<CancellableContinuation<Unit>> = mutableSetOf()

    suspend fun await() = suspendCancellableCoroutine { cont ->
        synchronized(this@TestContinuation) {
            if (_result == null) {
                _holder.add(cont)
            } else {
                cont.resume(Unit)
            }
        }
    }

    fun resume() {
        synchronized(this@TestContinuation) {
            check(_result == null) { "Resumed" }
            _result = Unit
            _holder.forEach { it.resume(Unit) }
        }
    }
}