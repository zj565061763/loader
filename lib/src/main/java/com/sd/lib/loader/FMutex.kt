package com.sd.lib.loader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class FMutex {
  private val _mutex = Mutex()

  /**
   * [withLock]中不允许嵌套调用[withLock]，否则会抛异常[IllegalStateException]，
   * 嵌套检测基于协程上下文实现，绕开协程上下文的嵌套调用（例如runBlocking，新开线程）检测不到，会死锁
   */
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
