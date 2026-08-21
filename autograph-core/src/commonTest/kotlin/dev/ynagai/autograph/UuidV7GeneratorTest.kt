package dev.ynagai.autograph

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

private const val FIXED_MILLIS = 1_700_000_000_000L

/** The 48-bit `unix_ts_ms` field. */
private fun Uuid.timestampMillis(): Long = toLongs { mostSignificantBits, _ -> mostSignificantBits ushr 16 }

class UuidV7GeneratorTest {

    @Test
    fun idsAreVersion7AndVariant10() {
        val id = UuidV7Generator { FIXED_MILLIS }.next().toString()
        assertEquals('7', id[14], "version nibble must be 7 in $id")
        assertTrue(id[19] in "89ab", "variant nibble must be 8, 9, a or b in $id")
    }

    @Test
    fun theTimestampFieldIsTheClockValue() {
        val id = UuidV7Generator { FIXED_MILLIS }.next()
        assertEquals(FIXED_MILLIS, id.timestampMillis())
    }

    @Test
    fun idsMintedInsideOneMillisecondStillSortInGenerationOrder() {
        // The case a naive timestamp-plus-random layout fails: with the clock frozen, only the
        // rand_a counter can order these.
        val generator = UuidV7Generator { FIXED_MILLIS }
        val ids = List(10_000) { generator.next().toString() }
        assertEquals(ids, ids.sorted(), "ids minted in one millisecond must sort in generation order")
        assertEquals(ids.size, ids.toSet().size, "ids must be unique")
    }

    @Test
    fun counterSaturationBorrowsFromTheNextMillisecond() {
        // 4096 counter values per millisecond, and the seed starts partway in, so 10k ids must
        // spill past the frozen clock's millisecond rather than repeat a (timestamp, counter) pair.
        val generator = UuidV7Generator { FIXED_MILLIS }
        val stamps = List(10_000) { generator.next().timestampMillis() }
        assertEquals(stamps, stamps.sorted(), "borrowed timestamps must not regress")
        assertEquals(FIXED_MILLIS, stamps.first())
        assertTrue(
            stamps.last() > FIXED_MILLIS,
            "10000 ids cannot fit in one millisecond's 4096 counter values, got ${stamps.last()}",
        )
    }

    @Test
    fun aClockThatJumpsBackwardsStillYieldsMonotonicIds() {
        var millis = FIXED_MILLIS
        val generator = UuidV7Generator { millis }
        val ids = mutableListOf<String>()
        repeat(500) {
            ids += generator.next().toString()
            millis -= 10
        }
        assertEquals(ids, ids.sorted(), "a regressing clock must not produce regressing ids")
        assertEquals(ids.size, ids.toSet().size, "ids must be unique")
    }

    @Test
    fun aRisingClockIsTrackedRatherThanCounted() {
        var millis = FIXED_MILLIS
        val generator = UuidV7Generator { millis }
        val stamps = List(100) { generator.next().timestampMillis().also { millis += 1 } }
        assertEquals(List(100) { FIXED_MILLIS + it }, stamps)
    }

    @Test
    fun concurrentCallersNeverGetTheSameId() = runTest {
        val generator = UuidV7Generator { FIXED_MILLIS }
        val ids = withContext(Dispatchers.Default) {
            List(8) { async { List(2_000) { generator.next().toString() } } }.awaitAll()
        }.flatten()
        assertEquals(16_000, ids.size)
        assertEquals(ids.size, ids.toSet().size, "the lock must serialise counter increments")
    }

    @Test
    fun aClockCrossingTheEpochDoesNotRegress() {
        // A device clock set before 1970 reports negative milliseconds, which do not fit the 48-bit
        // unix_ts_ms field. Encoding -1 and then 0 unclamped would sort the second id first.
        var millis = -5L
        val generator = UuidV7Generator { millis }
        val ids = List(20) { generator.next().toString().also { millis += 1 } }
        assertEquals(ids, ids.sorted(), "ids must not regress across the epoch boundary")
        assertEquals(ids.size, ids.toSet().size, "ids must be unique")
    }

    @Test
    fun aClockBeyondTheEncodableRangeStaysAValidUuidV7() {
        val id = UuidV7Generator { Long.MAX_VALUE }.next()
        assertEquals((1L shl 48) - 1, id.timestampMillis(), "the timestamp must not overflow its field")
        assertEquals('7', id.toString()[14], "version nibble must survive a clamped timestamp")
    }

    @Test
    fun idsStayUniqueOnceTheTimestampFieldIsExhausted() {
        // Past the encodable range there is nothing left to borrow on counter saturation, so
        // ordering within that millisecond degrades — but rand_b, not the counter, is what keeps
        // ids unique, so uniqueness must survive.
        val generator = UuidV7Generator { Long.MAX_VALUE }
        val ids = List(10_000) { generator.next().toString() }
        assertEquals(ids.size, ids.toSet().size, "ids must be unique even with the counter pinned")
    }

    @Test
    fun twoGeneratorsDoNotShareCounterState() {
        // Stamper drives its own instance off an injected clock; a test clock frozen in the past
        // must not be perturbed by, or perturb, the wall-clock instance behind EventId.UuidV7.
        val frozen = UuidV7Generator { FIXED_MILLIS }
        val later = UuidV7Generator { FIXED_MILLIS + 5_000 }
        repeat(100) { later.next() }
        assertEquals(FIXED_MILLIS, frozen.next().timestampMillis())
    }
}
