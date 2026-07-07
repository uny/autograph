package dev.ynagai.autograph

import android.content.Context
import android.content.SharedPreferences

public actual fun platformSeqStore(): SeqStore {
    val context = AutographAndroidContext.applicationContext
    if (context == null) {
        println(
            "Autograph: application Context is not available (androidx.startup may be disabled). " +
                "Falling back to in-memory storage; sequence counters will not survive restarts. " +
                "Provide a SeqStore explicitly via AutographConfig.store to fix this.",
        )
        return InMemorySeqStore()
    }
    return SharedPreferencesSeqStore(
        context.getSharedPreferences("dev.ynagai.autograph", Context.MODE_PRIVATE),
    )
}

internal object AutographAndroidContext {
    @Volatile
    var applicationContext: Context? = null
}

internal class SharedPreferencesSeqStore(
    private val prefs: SharedPreferences,
) : SeqStore {

    override fun getLong(key: String): Long? =
        if (prefs.contains(key)) prefs.getLong(key, 0) else null

    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
