package org.freewheel.core.telemetry

import java.io.File

actual class PlatformTelemetryFileIO actual constructor() : TelemetryFileIO {

    override fun readText(path: String): String? {
        return try {
            val file = File(path)
            if (file.exists()) file.readText(Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
    }

    override fun writeText(path: String, content: String): Boolean {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun delete(path: String): Boolean {
        return try {
            File(path).delete()
        } catch (_: Exception) {
            false
        }
    }

    override fun exists(path: String): Boolean {
        return try {
            File(path).exists()
        } catch (_: Exception) {
            false
        }
    }
}
