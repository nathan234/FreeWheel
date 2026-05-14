@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.freewheel.core.domain

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecSuccess
import platform.Security.errSecDuplicateItem
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS implementation of [WheelPasswordStore] backed by Keychain Services.
 *
 * Each per-MAC password is stored as a `kSecClassGenericPassword` item under
 * service [serviceName] with the MAC address as the account. Items are
 * accessible after first unlock and not synced to iCloud
 * (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`). When the Secure
 * Enclave is present, AES key material backing the encryption is hardware-
 * isolated; otherwise iOS falls back to software encryption with the
 * passcode-derived class key.
 *
 * No iCloud Keychain sync — passwords stay device-local. Restore-from-backup
 * yields no plaintext.
 *
 * Note on bridging: NSString/NSData and their CFStringRef/CFDataRef
 * counterparts are toll-free bridged in Objective-C; in Kotlin/Native we
 * lean on the same identity by casting the Foundation type to [CFTypeRef]
 * before handing it to CFDictionary. CFBridgingRetain/Release aren't
 * available in K/N — Kotlin's GC owns the NSString/NSData and the
 * CFDictionary holds a CF-side retain through `kCFTypeDictionaryValueCallBacks`,
 * so the lifetime is correct without explicit bridging retains.
 */
class KeychainWheelPasswordStore(
    private val serviceName: String = DEFAULT_SERVICE,
) : WheelPasswordStore {

    override fun getPassword(address: String): String? = memScoped {
        val query = baseQuery(address) {
            CFDictionaryAddValue(it, kSecMatchLimit, kSecMatchLimitOne)
            CFDictionaryAddValue(it, kSecReturnData, kCFBooleanTrue)
        }

        val resultVar = alloc<COpaquePointerVar>()
        val status = SecItemCopyMatching(query, resultVar.ptr)
        if (status != errSecSuccess) return@memScoped null

        // Returned data is toll-free bridged with NSData. Cast through CFTypeRef
        // to satisfy K/N's type system.
        @Suppress("CAST_NEVER_SUCCEEDS")
        val nsData = resultVar.value as? NSData ?: return@memScoped null
        nsDataToString(nsData)
    }

    override fun hasPassword(address: String): Boolean =
        getPassword(address)?.isNotBlank() == true

    override fun setPassword(address: String, password: String) {
        if (password.isBlank()) {
            clearPassword(address)
            return
        }
        val data = (password as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return

        val addQuery = baseQuery(address) {
            CFDictionaryAddValue(it, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            CFDictionaryAddValue(it, kSecValueData, data.asCFTypeRef())
        }
        val addStatus = SecItemAdd(addQuery, null)

        if (addStatus == errSecDuplicateItem) {
            val matchQuery = baseQuery(address)
            val updateAttrs = CFDictionaryCreateMutable(
                null,
                1,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            )!!
            CFDictionaryAddValue(updateAttrs, kSecValueData, data.asCFTypeRef())
            SecItemUpdate(matchQuery, updateAttrs)
        }
    }

    override fun clearPassword(address: String) {
        SecItemDelete(baseQuery(address))
    }

    override fun clearAll() {
        // Delete every kSecClassGenericPassword keyed under our service
        // (no kSecAttrAccount filter so all per-wheel entries are wiped).
        val query = CFDictionaryCreateMutable(
            null,
            2,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(query, kSecAttrService, (serviceName as NSString).asCFTypeRef())
        SecItemDelete(query)
    }

    /**
     * Build a Keychain query stamped with the standard service identity
     * (class, service name, account = address). [extras] adds any per-call
     * attributes (limit, return-data, value, accessible).
     *
     * The CFDictionary owns CF-side retains on its values via the
     * `kCFTypeDictionaryValueCallBacks` callback; Kotlin's GC keeps the
     * underlying NSString alive as long as the dictionary holds it.
     */
    private fun baseQuery(
        address: String,
        extras: ((CFDictionaryRef) -> Unit)? = null,
    ): CFDictionaryRef {
        val dict = CFDictionaryCreateMutable(
            null,
            8,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, (serviceName as NSString).asCFTypeRef())
        CFDictionaryAddValue(dict, kSecAttrAccount, (address as NSString).asCFTypeRef())
        extras?.invoke(dict)
        return dict
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any.asCFTypeRef(): CFTypeRef = this as CFTypeRef

    private fun nsDataToString(data: NSData): String? =
        NSString.create(data, NSUTF8StringEncoding) as String?

    companion object {
        const val DEFAULT_SERVICE = "org.freewheel.veteran.password"
    }
}
