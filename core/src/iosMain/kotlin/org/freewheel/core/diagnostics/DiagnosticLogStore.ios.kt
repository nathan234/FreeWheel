@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.freewheel.core.diagnostics

import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForUpdatingAtPath
import platform.Foundation.readDataToEndOfFile
import platform.Foundation.seekToEndOfFile
import platform.Foundation.synchronizeFile
import platform.Foundation.writeData

actual class DiagnosticLogStore {

    private val lock = Lock()

    private var dirPath: String? = null
    private var activePath: String? = null
    private var rotatedPath: String? = null
    private var maxBytes: Long = DEFAULT_MAX_BYTES

    actual fun configure(dirPath: String, maxBytes: Long): Unit = lock.withLock {
        if (this.dirPath == dirPath && this.maxBytes == maxBytes) return@withLock
        try {
            val manager = NSFileManager.defaultManager
            if (!manager.fileExistsAtPath(dirPath)) {
                manager.createDirectoryAtPath(
                    dirPath,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
            }
            this.dirPath = dirPath
            this.activePath = "$dirPath/$ACTIVE_FILE_NAME"
            this.rotatedPath = "$dirPath/$ROTATED_FILE_NAME"
            this.maxBytes = maxBytes
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to configure diagnostics dir: $dirPath", e)
        }
    }

    actual fun append(line: String): Unit = lock.withLock {
        val path = activePath ?: return@withLock
        try {
            val manager = NSFileManager.defaultManager
            if (!manager.fileExistsAtPath(path)) {
                manager.createFileAtPath(path, contents = null, attributes = null)
            }
            // Rotate if oversized — check via attributes lookup, cheap.
            if (currentSize(path) >= maxBytes) rotate()

            val handle = NSFileHandle.fileHandleForUpdatingAtPath(path) ?: run {
                Logger.w(TAG, "Could not open log handle: $path")
                return@withLock
            }
            try {
                handle.seekToEndOfFile()
                val data = (line + "\n").toNSData()
                if (data != null) handle.writeData(data)
                handle.synchronizeFile()
            } finally {
                handle.closeFile()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to append diagnostic line", e)
        }
    }

    actual fun readRecent(maxLines: Int): List<String> = lock.withLock {
        val active = activePath ?: return@withLock emptyList()
        val rotated = rotatedPath
        val lines = ArrayList<String>(maxLines)
        rotated?.let { if (NSFileManager.defaultManager.fileExistsAtPath(it)) lines.addAll(readAllLines(it)) }
        if (NSFileManager.defaultManager.fileExistsAtPath(active)) lines.addAll(readAllLines(active))
        if (lines.size <= maxLines) lines else lines.subList(lines.size - maxLines, lines.size)
    }

    actual fun activeFilePath(): String? = lock.withLock { activePath }

    actual fun clear(): Unit = lock.withLock {
        val manager = NSFileManager.defaultManager
        activePath?.let { if (manager.fileExistsAtPath(it)) manager.removeItemAtPath(it, null) }
        rotatedPath?.let { if (manager.fileExistsAtPath(it)) manager.removeItemAtPath(it, null) }
    }

    private fun rotate() {
        val active = activePath ?: return
        val rotated = rotatedPath ?: return
        val manager = NSFileManager.defaultManager
        try {
            if (manager.fileExistsAtPath(rotated)) manager.removeItemAtPath(rotated, null)
            manager.moveItemAtPath(active, toPath = rotated, error = null)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to rotate diagnostics", e)
        }
    }

    private fun currentSize(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return 0
        val size = attrs[NSFileSize] as? NSNumber ?: return 0
        return size.longLongValue
    }

    private fun readAllLines(path: String): List<String> {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return emptyList()
        return try {
            val data = handle.readDataToEndOfFile()
            val str = NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
            str?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to read $path", e)
            emptyList()
        } finally {
            handle.closeFile()
        }
    }

    private fun String.toNSData(): NSData? =
        NSString.create(string = this).dataUsingEncoding(NSUTF8StringEncoding)

    private companion object {
        const val TAG = "DiagnosticLogStore"
        const val ACTIVE_FILE_NAME = "events.jsonl"
        const val ROTATED_FILE_NAME = "events.jsonl.old"
        const val DEFAULT_MAX_BYTES = 5L * 1024 * 1024
    }
}
