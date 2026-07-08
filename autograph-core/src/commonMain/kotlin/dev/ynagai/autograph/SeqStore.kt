package dev.ynagai.autograph

/**
 * Minimal key-value persistence used for sequence counters and session state.
 *
 * The default implementation is backed by `SharedPreferences` on Android,
 * `NSUserDefaults` on iOS, and `java.util.prefs.Preferences` on the JVM.
 * Provide your own implementation via `AutographConfig.store` to control storage.
 */
public interface SeqStore {
    public fun getLong(key: String): Long?
    public fun putLong(key: String, value: Long)
    public fun getString(key: String): String?
    public fun putString(key: String, value: String)
    public fun remove(key: String)

    /**
     * Blocks until all prior writes are durably persisted (survive a process crash).
     *
     * The default platform stores buffer writes for latency; Autograph calls [flush] at the
     * points where a lost write would let a sequence number be reused after a crash — every
     * event under [SeqPersistence.EveryEvent], and at each chunk boundary/reservation under
     * [SeqPersistence.Chunked]. The default is a no-op, correct for already-synchronous stores
     * such as [InMemorySeqStore].
     */
    public fun flush() {}
}

/** Returns the platform-default [SeqStore]. */
public expect fun platformSeqStore(): SeqStore

/** A non-persistent [SeqStore] for tests or ephemeral configurations. */
public class InMemorySeqStore : SeqStore {
    private val longs = mutableMapOf<String, Long>()
    private val strings = mutableMapOf<String, String>()

    override fun getLong(key: String): Long? = longs[key]
    override fun putLong(key: String, value: Long) {
        longs[key] = value
    }

    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String) {
        strings[key] = value
    }

    override fun remove(key: String) {
        longs.remove(key)
        strings.remove(key)
    }
}
