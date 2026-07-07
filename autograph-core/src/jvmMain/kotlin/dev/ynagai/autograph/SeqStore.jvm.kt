package dev.ynagai.autograph

import java.util.prefs.Preferences

public actual fun platformSeqStore(): SeqStore =
    PreferencesSeqStore(Preferences.userRoot().node("dev.ynagai.autograph"))

internal class PreferencesSeqStore(
    private val prefs: Preferences,
) : SeqStore {

    override fun getLong(key: String): Long? {
        val marker = prefs.getLong(key, Long.MIN_VALUE)
        return if (marker == Long.MIN_VALUE && prefs.get(key, null) == null) null else marker
    }

    override fun putLong(key: String, value: Long) {
        prefs.putLong(key, value)
    }

    override fun getString(key: String): String? = prefs.get(key, null)

    override fun putString(key: String, value: String) {
        prefs.put(key, value)
    }

    override fun remove(key: String) {
        prefs.remove(key)
    }
}
