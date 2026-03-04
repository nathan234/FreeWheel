package org.freewheel.core.utils

import java.util.concurrent.locks.ReentrantLock

/**
 * Android/JVM implementation using ReentrantLock.
 */
actual class Lock {
    private val lock = ReentrantLock()

    actual fun lock() {
        lock.lock()
    }

    actual fun unlock() {
        lock.unlock()
    }
}
