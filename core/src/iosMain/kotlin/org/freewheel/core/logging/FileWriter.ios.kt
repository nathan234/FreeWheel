@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.freewheel.core.logging

import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.withLock
import platform.Foundation.*

actual class FileWriter actual constructor() {

    private val lock = Lock()
    private var fileHandle: NSFileHandle? = null

    actual fun open(path: String): Boolean = lock.withLock {
        val manager = NSFileManager.defaultManager
        val dir = (path as NSString).stringByDeletingLastPathComponent
        if (!manager.fileExistsAtPath(dir)) {
            manager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        // Create file (or truncate)
        manager.createFileAtPath(path, contents = null, attributes = null)
        fileHandle = NSFileHandle.fileHandleForWritingAtPath(path)
        fileHandle != null
    }

    actual fun writeLine(line: String) = lock.withLock {
        try {
            val data = (line + "\n").encodeToByteArray().toNSData()
            fileHandle?.writeData(data)
        } catch (e: Exception) {
            Logger.e("FileWriter", "Failed to write line", e)
        }
        Unit
    }

    actual fun close() = lock.withLock {
        fileHandle?.closeFile()
        fileHandle = null
    }
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return NSString.create(string = this.decodeToString())
        .dataUsingEncoding(NSUTF8StringEncoding) ?: NSData()
}
