package org.freewheel.core.utils

/**
 * A simple reentrant lock for cross-platform synchronization.
 * Uses platform-specific implementations (ReentrantLock on JVM, NSRecursiveLock on iOS).
 */
expect class Lock() {
    fun lock()
    fun unlock()
}

/**
 * Executes the given [block] while holding the lock.
 */
inline fun <T> Lock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
