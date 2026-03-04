package org.freewheel.core.logging

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

actual class FileWriter actual constructor() {

    private var writer: BufferedWriter? = null

    actual fun open(path: String): Boolean {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8))
            true
        } catch (e: Exception) {
            writer = null
            false
        }
    }

    actual fun writeLine(line: String) {
        writer?.apply {
            write(line)
            newLine()
            flush()
        }
    }

    actual fun close() {
        try {
            writer?.close()
        } catch (_: Exception) {
        } finally {
            writer = null
        }
    }
}
