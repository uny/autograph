package dev.ynagai.autograph

internal actual fun platformSeqStoreDir(): String? {
    val home = System.getProperty("user.home")
        ?: System.getProperty("java.io.tmpdir")
        ?: return null
    return "$home/.autograph"
}
