package dev.ynagai.autograph

import android.content.Context

internal actual fun platformSeqStoreDir(): String? {
    val context = AutographAndroidContext.applicationContext
    if (context == null) {
        println(
            "Autograph: application Context is not available (androidx.startup may be disabled). " +
                "Falling back to in-memory storage; sequence counters will not survive restarts. " +
                "Provide a SeqStore explicitly via AutographConfig.store to fix this.",
        )
        return null
    }
    return "${context.filesDir.absolutePath}/autograph"
}

internal object AutographAndroidContext {
    @Volatile
    var applicationContext: Context? = null
}
