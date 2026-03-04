package org.freewheel.core.utils

import platform.Foundation.NSRecursiveLock

/**
 * iOS implementation using NSRecursiveLock.
 */
actual class Lock {
    private val lock = NSRecursiveLock()

    actual fun lock() {
        lock.lock()
    }

    actual fun unlock() {
        lock.unlock()
    }
}
