package dev.ynagai.autograph

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileSeqStoreTest {

    private val dirPath = Path(SystemTemporaryDirectory, "autograph-fileseqstore-test")
    private val dir = dirPath.toString()

    @BeforeTest
    fun clean() = cleanup()

    @AfterTest
    fun tearDown() = cleanup()

    private fun cleanup() {
        listOf("autograph-seq.json", "autograph-seq.json.tmp").forEach {
            val p = Path(dir, it)
            if (SystemFileSystem.exists(p)) SystemFileSystem.delete(p)
        }
        if (SystemFileSystem.exists(dirPath)) SystemFileSystem.delete(dirPath)
    }

    @Test
    fun flushedStateSurvivesAcrossInstances() {
        FileSeqStore(dir).apply {
            putLong("autograph.seq.session", 42L)
            putString("autograph.session.id", "sess-1")
            flush()
        }
        // Simulate a restart: a fresh instance must read the persisted state.
        val resumed = FileSeqStore(dir)
        assertEquals(42L, resumed.getLong("autograph.seq.session"))
        assertEquals("sess-1", resumed.getString("autograph.session.id"))
    }

    @Test
    fun onlyFlushedWritesAreDurable() {
        FileSeqStore(dir).apply { putLong("seq", 7L); flush() }
        // A write without flush() must not be visible to a new instance (models a crash before flush).
        val s = FileSeqStore(dir).apply { putLong("seq", 8L) }
        assertEquals(8L, s.getLong("seq"), "unflushed write is visible within the same instance")
        assertEquals(7L, FileSeqStore(dir).getLong("seq"), "only the flushed value survives a restart")
    }

    @Test
    fun longAndStringValuesDoNotAlias() {
        FileSeqStore(dir).apply {
            putLong("n", 5L)
            putString("s", "5")
            flush()
        }
        val r = FileSeqStore(dir)
        assertEquals(5L, r.getLong("n"))
        assertEquals("5", r.getString("s"))
        assertNull(r.getLong("s"), "a string value must not be read back as a long")
        assertNull(r.getString("n"), "a long value must not be read back as a string")
    }

    @Test
    fun absentStoreReadsAsEmpty() {
        val s = FileSeqStore(dir)
        assertNull(s.getLong("missing"))
        assertNull(s.getString("missing"))
    }
}
