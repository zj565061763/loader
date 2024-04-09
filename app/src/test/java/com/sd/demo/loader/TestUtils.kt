package com.sd.demo.loader

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

enum class LoaderResultState {
    Initial,
    Success,
    Failure,
}

class TestContinuation {
    private var _cont: CancellableContinuation<Unit>? = null

    suspend fun await() = suspendCancellableCoroutine { cont ->
        synchronized(this@TestContinuation) {
            if (_cont != null) error("Last await not resumed.")
            _cont = cont
        }
    }

    fun resume() {
        synchronized(this@TestContinuation) {
            _cont?.resume(Unit)
            _cont = null
        }
    }
}

suspend fun AtomicBoolean.awaitTrue() {
    while (!get()) {
        delay(10)
    }
}