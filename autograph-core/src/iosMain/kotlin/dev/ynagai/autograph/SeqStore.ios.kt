package dev.ynagai.autograph

import platform.Foundation.NSUserDefaults

public actual fun platformSeqStore(): SeqStore =
    UserDefaultsSeqStore(NSUserDefaults.standardUserDefaults)

internal class UserDefaultsSeqStore(
    private val defaults: NSUserDefaults,
) : SeqStore {

    override fun getLong(key: String): Long? =
        if (defaults.objectForKey(key) != null) defaults.integerForKey(key) else null

    override fun putLong(key: String, value: Long) {
        defaults.setInteger(value, key)
    }

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
