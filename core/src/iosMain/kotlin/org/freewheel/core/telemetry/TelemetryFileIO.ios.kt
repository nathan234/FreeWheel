@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.freewheel.core.telemetry

import platform.Foundation.*

actual class PlatformTelemetryFileIO actual constructor() : TelemetryFileIO {

    override fun readText(path: String): String? {
        return try {
            val nsString = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
            nsString as? String
        } catch (_: Exception) {
            null
        }
    }

    override fun writeText(path: String, content: String): Boolean {
        return try {
            val dir = (path as NSString).stringByDeletingLastPathComponent
            val manager = NSFileManager.defaultManager
            if (!manager.fileExistsAtPath(dir)) {
                manager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
            }
            (content as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        } catch (_: Exception) {
            false
        }
    }

    override fun delete(path: String): Boolean {
        return try {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        } catch (_: Exception) {
            false
        }
    }

    override fun exists(path: String): Boolean {
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }
}
