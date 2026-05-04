package org.freewheel.core.domain

import platform.Foundation.NSUserDefaults

/**
 * iOS implementation of [KeyValueStore] backed by [NSUserDefaults].
 */
class UserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults
) : KeyValueStore {

    override fun getString(key: String, default: String?): String? =
        defaults.stringForKey(key) ?: default

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String): Set<String> {
        val array = defaults.stringArrayForKey(key) ?: return emptySet()
        return array.toSet() as Set<String>
    }

    override fun putStringSet(key: String, value: Set<String>) {
        defaults.setObject(value.toList(), forKey = key)
    }

    override fun getLong(key: String, default: Long): Long {
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key)
        } else {
            default
        }
    }

    override fun putLong(key: String, value: Long) {
        defaults.setInteger(value, forKey = key)
    }

    override fun getDouble(key: String, default: Double): Double {
        return if (defaults.objectForKey(key) != null) {
            defaults.doubleForKey(key)
        } else {
            default
        }
    }

    override fun putDouble(key: String, value: Double) {
        defaults.setDouble(value, forKey = key)
    }

    override fun getBool(key: String, default: Boolean): Boolean {
        return if (defaults.objectForKey(key) != null) {
            defaults.boolForKey(key)
        } else {
            default
        }
    }

    override fun putBool(key: String, value: Boolean) {
        defaults.setBool(value, forKey = key)
    }

    override fun getInt(key: String, default: Int): Int {
        return if (defaults.objectForKey(key) != null) {
            defaults.integerForKey(key).toInt()
        } else {
            default
        }
    }

    override fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), forKey = key)
    }

    override fun contains(key: String): Boolean =
        defaults.objectForKey(key) != null

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
