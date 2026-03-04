package org.freewheel.core.utils

import platform.Foundation.NSLog

/**
 * iOS implementation using NSLog.
 */
actual object Logger {
    actual fun d(tag: String, message: String) {
        NSLog("[$tag] D: $message")
    }

    actual fun i(tag: String, message: String) {
        NSLog("[$tag] I: $message")
    }

    actual fun w(tag: String, message: String) {
        NSLog("[$tag] W: $message")
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            NSLog("[$tag] E: $message - ${throwable.message}\n${throwable.stackTraceToString()}")
        } else {
            NSLog("[$tag] E: $message")
        }
    }
}
