package org.freewheel.core.domain

/**
 * Platform-agnostic key-value store interface.
 *
 * Android: backed by SharedPreferences.
 * iOS: backed by NSUserDefaults.
 */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)
    fun getStringSet(key: String): Set<String>
    fun putStringSet(key: String, value: Set<String>)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getDouble(key: String, default: Double): Double
    fun putDouble(key: String, value: Double)
    fun getBool(key: String, default: Boolean): Boolean
    fun putBool(key: String, value: Boolean)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun contains(key: String): Boolean
    fun remove(key: String)
}
