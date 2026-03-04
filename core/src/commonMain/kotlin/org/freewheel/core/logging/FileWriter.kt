package org.freewheel.core.logging

/**
 * Simple line-oriented file writer for CSV logging.
 * Platform implementations use BufferedWriter (JVM) or NSFileHandle (iOS).
 */
expect class FileWriter() {
    /** Create the file (or truncate if exists) and prepare for writing. Returns true on success. */
    fun open(path: String): Boolean

    /** Append a line (with trailing newline) to the open file. */
    fun writeLine(line: String)

    /** Flush and close the file. */
    fun close()
}
