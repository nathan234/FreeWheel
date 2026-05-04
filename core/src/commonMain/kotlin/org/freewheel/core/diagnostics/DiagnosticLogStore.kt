package org.freewheel.core.diagnostics

/**
 * Append-only persistent store for diagnostic events. Backed by a JSONL file
 * on each platform. Implementations must be thread-safe and flush after each
 * append — losing events to a crash defeats the purpose of the log.
 *
 * On rotation: when the active file exceeds [maxBytes], it is renamed to
 * "<file>.old" (replacing any prior .old) and a fresh active file is started.
 * One generation kept; older logs are dropped.
 */
expect class DiagnosticLogStore {

    /**
     * Configures the store. Must be called once before any [append] / [readRecent].
     * Idempotent — calling again with the same params is a no-op.
     *
     * @param dirPath Absolute path to the directory that will hold events.jsonl.
     * @param maxBytes Soft size cap for the active file (default 5 MB).
     */
    fun configure(dirPath: String, maxBytes: Long = 5L * 1024 * 1024)

    /** Appends one event line. Flushes to disk before returning. */
    fun append(line: String)

    /**
     * Returns the most recent N lines from the active file plus the rotated
     * file (if any), oldest-first.
     */
    fun readRecent(maxLines: Int = 500): List<String>

    /** Returns the absolute path to the active log file (for share intents). */
    fun activeFilePath(): String?

    /** Truncates the active log file and removes the rotated file. */
    fun clear()
}
