package org.freewheel.core.utils

import android.util.Log

/**
 * Android implementation using Android's Log class.
 * In debug builds, Timber may be initialized to intercept these logs.
 *
 * Note: In unit tests, android.util.Log is not available and will throw.
 * This implementation catches and ignores those exceptions to allow tests to pass.
 */
actual object Logger {
    actual fun d(tag: String, message: String) {
        try {
            Log.d(tag, message)
        } catch (e: RuntimeException) {
            // Log not available in unit tests
            println("[$tag] D: $message")
        }
    }

    actual fun i(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (e: RuntimeException) {
            // Log not available in unit tests
            println("[$tag] I: $message")
        }
    }

    actual fun w(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (e: RuntimeException) {
            // Log not available in unit tests
            println("[$tag] W: $message")
        }
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        try {
            if (throwable != null) {
                Log.e(tag, message, throwable)
            } else {
                Log.e(tag, message)
            }
        } catch (e: RuntimeException) {
            // Log not available in unit tests
            if (throwable != null) {
                println("[$tag] E: $message - ${throwable.message}")
            } else {
                println("[$tag] E: $message")
            }
        }
    }
}
