package org.freewheel.core.utils

/**
 * Cross-platform logging interface for KMP code.
 * Platform-specific implementations use native logging frameworks.
 */
expect object Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Extension functions for convenient logging with class-based tags.
 */
inline fun <reified T> T.logD(message: String) {
    Logger.d(T::class.simpleName ?: "Unknown", message)
}

inline fun <reified T> T.logI(message: String) {
    Logger.i(T::class.simpleName ?: "Unknown", message)
}

inline fun <reified T> T.logW(message: String) {
    Logger.w(T::class.simpleName ?: "Unknown", message)
}

inline fun <reified T> T.logE(message: String, throwable: Throwable? = null) {
    Logger.e(T::class.simpleName ?: "Unknown", message, throwable)
}
