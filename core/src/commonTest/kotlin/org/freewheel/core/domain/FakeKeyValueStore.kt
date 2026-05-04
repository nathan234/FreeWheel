package org.freewheel.core.domain

/**
 * In-memory [KeyValueStore] for testing.
 */
class FakeKeyValueStore : KeyValueStore {
    private val strings = mutableMapOf<String, String?>()
    private val stringSets = mutableMapOf<String, Set<String>>()
    private val longs = mutableMapOf<String, Long>()
    private val doubles = mutableMapOf<String, Double>()
    private val bools = mutableMapOf<String, Boolean>()
    private val ints = mutableMapOf<String, Int>()

    override fun getString(key: String, default: String?): String? =
        if (key in strings) strings[key] else default

    override fun putString(key: String, value: String) {
        strings[key] = value
    }

    override fun getStringSet(key: String): Set<String> =
        stringSets[key] ?: emptySet()

    override fun putStringSet(key: String, value: Set<String>) {
        stringSets[key] = value.toSet()
    }

    override fun getLong(key: String, default: Long): Long =
        longs[key] ?: default

    override fun putLong(key: String, value: Long) {
        longs[key] = value
    }

    override fun getDouble(key: String, default: Double): Double =
        doubles[key] ?: default

    override fun putDouble(key: String, value: Double) {
        doubles[key] = value
    }

    override fun getBool(key: String, default: Boolean): Boolean =
        bools[key] ?: default

    override fun putBool(key: String, value: Boolean) {
        bools[key] = value
    }

    override fun getInt(key: String, default: Int): Int =
        ints[key] ?: default

    override fun putInt(key: String, value: Int) {
        ints[key] = value
    }

    override fun contains(key: String): Boolean =
        key in strings || key in stringSets || key in longs ||
            key in doubles || key in bools || key in ints

    override fun remove(key: String) {
        strings.remove(key)
        stringSets.remove(key)
        longs.remove(key)
        doubles.remove(key)
        bools.remove(key)
        ints.remove(key)
    }
}
