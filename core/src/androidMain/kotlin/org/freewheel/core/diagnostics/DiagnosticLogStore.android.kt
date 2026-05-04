package org.freewheel.core.diagnostics

import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

actual class DiagnosticLogStore {

    private val lock = Lock()

    private var dir: File? = null
    private var activeFile: File? = null
    private var maxBytes: Long = DEFAULT_MAX_BYTES

    actual fun configure(dirPath: String, maxBytes: Long): Unit = lock.withLock {
        val target = File(dirPath)
        if (activeFile?.parentFile == target && this.maxBytes == maxBytes) return@withLock
        try {
            target.mkdirs()
            dir = target
            activeFile = File(target, ACTIVE_FILE_NAME)
            this.maxBytes = maxBytes
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to configure diagnostics dir: $dirPath", e)
        }
    }

    actual fun append(line: String): Unit = lock.withLock {
        val file = activeFile ?: return@withLock
        try {
            if (file.exists() && file.length() >= maxBytes) rotate()
            FileOutputStream(file, true).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { w ->
                    w.write(line)
                    w.write("\n")
                    w.flush()
                }
                fos.fd.sync()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to append diagnostic line", e)
        }
    }

    actual fun readRecent(maxLines: Int): List<String> = lock.withLock {
        val file = activeFile ?: return@withLock emptyList()
        val rotated = rotatedFile()
        val lines = ArrayList<String>(maxLines)
        try {
            if (rotated?.exists() == true) lines.addAll(rotated.readLines(Charsets.UTF_8))
            if (file.exists()) lines.addAll(file.readLines(Charsets.UTF_8))
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to read diagnostics", e)
            return@withLock emptyList()
        }
        if (lines.size <= maxLines) lines else lines.subList(lines.size - maxLines, lines.size)
    }

    actual fun activeFilePath(): String? = lock.withLock { activeFile?.absolutePath }

    actual fun clear(): Unit = lock.withLock {
        try {
            activeFile?.takeIf { it.exists() }?.delete()
            rotatedFile()?.takeIf { it.exists() }?.delete()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to clear diagnostics", e)
        }
    }

    private fun rotate() {
        val file = activeFile ?: return
        val rotated = rotatedFile() ?: return
        try {
            if (rotated.exists()) rotated.delete()
            file.renameTo(rotated)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to rotate diagnostics", e)
        }
    }

    private fun rotatedFile(): File? = dir?.let { File(it, ROTATED_FILE_NAME) }

    private companion object {
        const val TAG = "DiagnosticLogStore"
        const val ACTIVE_FILE_NAME = "events.jsonl"
        const val ROTATED_FILE_NAME = "events.jsonl.old"
        const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
    }
}
