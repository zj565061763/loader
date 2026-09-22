package com.sd.lib.loader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 提供互斥执行，并阻止同一实例在锁内嵌套调用。
 *
 * 嵌套检测依赖协程上下文，通过 `runBlocking` 或新线程绕开原上下文时无法检测，可能导致死锁。
 */
class FMutex {
  private val _mutex = Mutex()

  /** 在互斥锁内执行[action]，同一实例嵌套调用时抛出[IllegalStateException] */
  suspend fun <T> withLock(action: suspend () -> T): T {
    checkNested()
    return _mutex.withLock {
      withContext(NestedElement(_nestedKey)) {
        action()
      }
    }
  }

  /** 检查是否嵌套调用，如果嵌套则抛异常[IllegalStateException] */
  internal suspend fun checkNested() {
    if (currentCoroutineContext()[_nestedKey] != null) error("Nested invoke")
  }

  private val _nestedKey = object : CoroutineContext.Key<NestedElement> {}

  private class NestedElement(key: CoroutineContext.Key<NestedElement>) : AbstractCoroutineContextElement(key)
}
