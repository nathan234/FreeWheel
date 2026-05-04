package org.freewheel.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RideReconcilerTest {

    private val header = "date,time,speed,voltage,phase_current,current,power,torque,pwm," +
        "battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert"

    private fun row(secOffset: Int, speed: Double = 10.0, dist: Long = 0L): String {
        val sec = secOffset.toString().padStart(2, '0')
        return "2026-05-03,14:30:$sec.000,${speed},84.0,5.0,5.0,500.0,0,40,90,$dist,0,30,28,0,0,Sport,"
    }

    private fun csvWith(samples: Int): String {
        val rows = (0 until samples).map { row(it, 10.0 + it, it.toLong()) }
        return (listOf(header) + rows).joinToString("\n")
    }

    private class FakeIndex(initial: List<IndexEntry> = emptyList()) : RideIndex {
        val entries = initial.toMutableList()
        val added = mutableListOf<RideMetadata>()
        val removed = mutableListOf<String>()

        override fun list(): List<IndexEntry> = entries.toList()
        override fun add(metadata: RideMetadata) {
            entries.add(IndexEntry(metadata.fileName))
            added.add(metadata)
        }
        override fun removeByFileName(fileName: String) {
            entries.removeAll { it.fileName == fileName }
            removed.add(fileName)
        }
    }

    private class FakeFs(private val files: Map<String, String>) : RideFileSystem {
        override fun listCsvFiles(): List<RideFile> =
            files.keys.map { RideFile(it, files[it]?.length?.toLong() ?: 0) }
        override fun readContent(fileName: String): String? = files[fileName]
    }

    @Test
    fun `recovers orphan CSV not in index`() {
        val fs = FakeFs(mapOf("ride.csv" to csvWith(20)))
        val index = FakeIndex()

        val r = RideReconciler(index, fs).reconcile()

        assertEquals(1, r.recovered)
        assertEquals(0, r.phantom)
        assertEquals(1, index.added.size)
        assertEquals("ride.csv", index.added[0].fileName)
    }

    @Test
    fun `drops phantom index entry when CSV is missing`() {
        val fs = FakeFs(emptyMap())
        val index = FakeIndex(listOf(IndexEntry("gone.csv")))

        val r = RideReconciler(index, fs).reconcile()

        assertEquals(0, r.recovered)
        assertEquals(1, r.phantom)
        assertEquals(listOf("gone.csv"), index.removed)
    }

    @Test
    fun `does nothing when CSV and index agree`() {
        val fs = FakeFs(mapOf("a.csv" to csvWith(20)))
        val index = FakeIndex(listOf(IndexEntry("a.csv")))

        val r = RideReconciler(index, fs).reconcile()

        assertEquals(0, r.recovered)
        assertEquals(0, r.phantom)
        assertTrue(index.added.isEmpty())
        assertTrue(index.removed.isEmpty())
    }

    @Test
    fun `skips rides below sanity threshold`() {
        val fs = FakeFs(mapOf("tiny.csv" to csvWith(3))) // < 5 samples and < 10s
        val index = FakeIndex()

        val r = RideReconciler(index, fs).reconcile()

        assertEquals(0, r.recovered)
        assertEquals(1, r.skipped)
        assertTrue(index.added.isEmpty())
    }

    @Test
    fun `flags corrupt CSV with no header`() {
        val fs = FakeFs(mapOf("bad.csv" to "not even a header"))
        val index = FakeIndex()

        val r = RideReconciler(index, fs).reconcile()

        assertEquals(0, r.recovered)
        assertEquals(1, r.corrupt)
        assertTrue(index.added.isEmpty())
    }

    @Test
    fun `flags corrupt CSV that is empty`() {
        val fs = FakeFs(mapOf("empty.csv" to ""))
        val index = FakeIndex()

        val r = RideReconciler(index, fs).reconcile()

        assertEquals(1, r.corrupt)
    }

    @Test
    fun `processes orphans and phantoms in one pass`() {
        val fs = FakeFs(mapOf("new.csv" to csvWith(20)))
        val index = FakeIndex(listOf(IndexEntry("missing.csv")))

        val r = RideReconciler(index, fs).reconcile()

        assertEquals(1, r.recovered)
        assertEquals(1, r.phantom)
    }
}
