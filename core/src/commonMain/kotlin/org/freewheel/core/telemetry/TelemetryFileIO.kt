package org.freewheel.core.telemetry

/**
 * Interface for telemetry history file persistence.
 * Platform implementations provided via [PlatformTelemetryFileIO].
 */
interface TelemetryFileIO {
    fun readText(path: String): String?
    fun writeText(path: String, content: String): Boolean
    fun delete(path: String): Boolean
    fun exists(path: String): Boolean
}

/**
 * Platform-specific file I/O implementation.
 * Uses java.io.File on Android and NSFileManager on iOS.
 */
expect class PlatformTelemetryFileIO() : TelemetryFileIO
