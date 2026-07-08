package dev.ynagai.autograph

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Minimal key-value persistence used for sequence counters and session state.
 *
 * The default implementation ([FileSeqStore], via [platformSeqStore]) is a small file written
 * atomically under the app's private storage on every platform. Provide your own implementation
 * via `AutographConfig.store` to control storage.
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
     * Autograph calls [flush] at the points where a lost write would let a sequence number be
     * reused after a crash — every event under [SeqPersistence.EveryEvent], and at each chunk
     * boundary/reservation under [SeqPersistence.Chunked]. The default is a no-op, correct for
     * already-synchronous stores such as [InMemorySeqStore].
     */
    public fun flush() {}
}

/**
 * Returns the platform-default [SeqStore]: a [FileSeqStore] under the app's private data
 * directory, or an [InMemorySeqStore] if no writable directory is available (in which case
 * counters do not survive a restart).
 */
public fun platformSeqStore(): SeqStore {
    val dir = platformSeqStoreDir() ?: return InMemorySeqStore()
    return FileSeqStore(dir)
}

/** The app-private directory for Autograph's state file, or null if none is available. */
internal expect fun platformSeqStoreDir(): String?

/**
 * A [SeqStore] backed by a single small JSON file written **atomically**.
 *
 * Writes accumulate in memory and are persisted on [flush] by writing a temp file and renaming
 * it over the target. The rename is atomic, so a reader (this process on the next launch, or a
 * concurrent one) always sees either the complete previous file or the complete new one — never a
 * torn write. Because the bytes are in the OS page cache once the write returns, they survive a
 * process crash (OOM/SIGKILL) even without an explicit fsync — which is exactly the crash model
 * Autograph's "never reuse a sequence number" guarantee targets. (Surviving a kernel panic or
 * power loss would additionally require an fsync, which the portable file APIs here do not expose.)
 *
 * This replaces the buffered platform key-value stores (SharedPreferences.apply() / NSUserDefaults),
 * whose pending writes live in *process* memory and are lost on a hard kill.
 */
internal class FileSeqStore(
    directory: String,
    fileName: String = "autograph-seq.json",
) : SeqStore {

    private val dir = Path(directory)
    private val file = Path(directory, fileName)
    private val tmp = Path(directory, "$fileName.tmp")
    private val values: MutableMap<String, JsonPrimitive> = load()

    private fun load(): MutableMap<String, JsonPrimitive> {
        if (!SystemFileSystem.exists(file)) return mutableMapOf()
        return try {
            val text = SystemFileSystem.source(file).buffered().use { it.readString() }
            Json.parseToJsonElement(text).jsonObject
                .mapValuesTo(mutableMapOf()) { (_, v) -> v.jsonPrimitive }
        } catch (_: Exception) {
            // A corrupt/partial file (which atomic writes should prevent) is treated as absent:
            // starting a fresh session is far better than crashing on app startup.
            mutableMapOf()
        }
    }

    override fun getLong(key: String): Long? = values[key]?.let { if (it.isString) null else it.longOrNull }

    override fun putLong(key: String, value: Long) {
        values[key] = JsonPrimitive(value)
    }

    override fun getString(key: String): String? = values[key]?.let { if (it.isString) it.content else null }

    override fun putString(key: String, value: String) {
        values[key] = JsonPrimitive(value)
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun flush() {
        SystemFileSystem.createDirectories(dir)
        SystemFileSystem.sink(tmp).buffered().use { it.writeString(JsonObject(values).toString()) }
        SystemFileSystem.atomicMove(tmp, file)
    }
}

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
