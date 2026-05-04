@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.freewheel.core.logging

import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringByAppendingPathComponent

/**
 * Swift-facing entry point for the cross-platform [RideReconciler].
 *
 * On iOS the index lives in [RideStore] (Swift) and CSVs live in the app's
 * Documents directory. Rather than try to expose Kotlin interfaces to Swift,
 * this helper takes plain function-type callbacks for the index operations
 * and provides a built-in [RideFileSystem] backed by NSFileManager.
 */
class RideReconcilerHelper {

    fun reconcile(
        ridesDir: String,
        listIndex: () -> List<IndexEntry>,
        addToIndex: (RideMetadata) -> Unit,
        removeFromIndex: (String) -> Unit,
        sanitySamples: Int = 5,
        sanityDurationSec: Long = 10L,
    ): RideReconciler.Result {
        val index = object : RideIndex {
            override fun list(): List<IndexEntry> = listIndex()
            override fun add(metadata: RideMetadata) = addToIndex(metadata)
            override fun removeByFileName(fileName: String) = removeFromIndex(fileName)
        }
        val fs = IosRideFileSystem(ridesDir)
        return RideReconciler(index, fs, sanitySamples, sanityDurationSec).reconcile()
    }
}

/**
 * iOS-side [RideFileSystem] implementation backed by NSFileManager.
 * Public so Swift code or tests can reach it directly if useful.
 */
class IosRideFileSystem(private val dirPath: String) : RideFileSystem {

    override fun listCsvFiles(): List<RideFile> {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(dirPath)) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val raw = manager.contentsOfDirectoryAtPath(dirPath, error = null)
            as? List<String>
            ?: return emptyList()
        val out = ArrayList<RideFile>(raw.size)
        for (name in raw) {
            if (!name.endsWith(".csv", ignoreCase = true)) continue
            val full = (dirPath as NSString).stringByAppendingPathComponent(name)
            val attrs = manager.attributesOfItemAtPath(full, null)
            val size = (attrs?.get(platform.Foundation.NSFileSize)
                as? platform.Foundation.NSNumber)?.longLongValue ?: 0L
            out.add(RideFile(name, size))
        }
        return out
    }

    override fun readContent(fileName: String): String? {
        val full = (dirPath as NSString).stringByAppendingPathComponent(fileName)
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(full)) return null
        val data = NSData.dataWithContentsOfFile(full) ?: return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
    }
}
