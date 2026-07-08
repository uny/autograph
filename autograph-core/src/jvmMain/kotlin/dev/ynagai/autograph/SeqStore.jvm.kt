package dev.ynagai.autograph

internal actual fun platformSeqStoreDir(): String? {
    // System.getProperty can throw SecurityException under a SecurityManager; fall back to the
    // in-memory store (via a null dir) rather than crashing on init, mirroring the Android/iOS actuals.
    val home = try {
        System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir")
    } catch (_: SecurityException) {
        null
    } ?: return null
    return "$home/.autograph"
}
