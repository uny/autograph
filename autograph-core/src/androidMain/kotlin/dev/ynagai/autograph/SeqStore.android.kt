package dev.ynagai.autograph

import android.content.Context

internal actual fun platformSeqStoreDir(): String? {
    val context = AutographAndroidContext.applicationContext
    if (context == null) {
        // Console, not AutographConfig.logger: platformSeqStore() is a public standalone function
        // (SeqStore.kt) callable with no Autograph config in scope, so there is no configured logger to
        // route to here. A one-time storage-fallback notice, not a running-tracker diagnostic.
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
