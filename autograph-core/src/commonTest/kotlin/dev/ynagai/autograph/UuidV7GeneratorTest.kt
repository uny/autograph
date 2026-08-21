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

private const val MAX_TIMESTAMP = (1L shl 48) - 1

/** The 48-bit `unix_ts_ms` field. */
private fun Uuid.timestampMillis(): Long = toLongs { mostSignificantBits, _ -> mostSignificantBits ushr 16 }

/** Everything that orders one id against another: `unix_ts_ms` plus the `rand_a` counter. */
private fun Uuid.sortablePrefix(): Long = toLongs { mostSignificantBits, _ -> mostSignificantBits }

/** The `rand_a` counter, which orders ids sharing a millisecond. */
private fun Uuid.counter(): Int = toLongs { mostSignificantBits, _ -> (mostSignificantBits and 0xFFF).toInt() }

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
        // The case a naive timestamp-plus-random layout fails. 10k ids outrun one millisecond's
        // 4096 counter values, so the clock being frozen does not mean the counter orders all of
        // them — it orders each borrowed millisecond's ~2-4k, and the borrowed timestamp orders the
        // groups. Shrinking the count below ~2k would drop the borrow path this also covers.
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
            List(8) { async { List(2_000) { generator.next() } } }.awaitAll()
        }.flatten()
        assertEquals(16_000, ids.size)
        assertEquals(ids.size, ids.map { it.toString() }.toSet().size, "ids must be unique")
        // Uniqueness alone cannot see the lock: rand_b is 62 fresh random bits per id, so 16k ids
        // stay distinct even with `synchronized` deleted. The (timestamp, counter) prefix is the
        // part the lock actually protects — racing `counter += 1` writes duplicate one pair.
        assertEquals(
            ids.size,
            ids.map { it.sortablePrefix() }.toSet().size,
            "the lock must serialise counter increments, so every (timestamp, counter) pair is unique",
        )
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
    fun aClockBeyondTheEncodableRangeFallsBackToTheLastGoodMillisecond() {
        // Asserting merely "inside the 48-bit field" would prove nothing: timestampMillis() is
        // `msb ushr 16`, so every possible Long lands in 0..MAX_TIMESTAMP by construction and even
        // a pure Uuid.random() would pass. The behaviour worth pinning is *which* value: an
        // unusable reading yields the last good millisecond, and on a first call there is none, so
        // it is 0 — not MAX_TIMESTAMP, which is what clamping would have given and what pinned the
        // generator permanently.
        val id = UuidV7Generator { Long.MAX_VALUE }.next()
        assertEquals(0L, id.timestampMillis(), "an unusable first reading must not be clamped up to the ceiling")
        assertEquals('7', id.toString()[14], "version nibble must survive an unusable timestamp")
    }

    @Test
    fun oneUnusableClockReadingDoesNotPinTheGeneratorForever() {
        // An out-of-range reading must not be *adopted* as the timestamp. Clamping it down to
        // MAX_TIMESTAMP would leave lastMillis at the ceiling for good: every later reading then
        // fails `now > lastMillis`, the counter saturates, and the exhausted-field branch has
        // nothing to borrow — so a single garbage reading (a wrong-unit clock handing over
        // nanoseconds, say) would cost ordering permanently, even after the clock is corrected.
        var millis = Long.MAX_VALUE
        val generator = UuidV7Generator { millis }
        generator.next()
        millis = FIXED_MILLIS
        val ids = List(6_000) { generator.next().toString() }
        assertEquals(ids, ids.sorted(), "ordering must recover once the clock is usable again")
        assertEquals(ids.size, ids.toSet().size, "ids must be unique")
    }

    @Test
    fun theTimestampFieldNeverWrapsOnceItIsExhausted() {
        // A clock that genuinely reads the ceiling is the one way to exhaust the field, and there
        // the saturation branch must stop borrowing: `lastMillis + 1` would be 1 shl 48, whose
        // `shl 16` is 0, so unix_ts_ms would wrap and every later id would sort before all of them.
        // Ordering inside that millisecond is what is given up; uniqueness is rand_b's job.
        val generator = UuidV7Generator { MAX_TIMESTAMP }
        val ids = List(10_000) { generator.next() }
        assertTrue(
            ids.all { it.timestampMillis() == MAX_TIMESTAMP },
            "the timestamp must stay pinned at the ceiling, never wrap to 0",
        )
        assertEquals(
            ids.size,
            ids.map { it.toString() }.toSet().size,
            "ids must be unique even with the counter pinned",
        )
    }

    @Test
    fun eachMillisecondsFirstCounterIsSeededRandomlyIntoTheLowHalf() {
        // The counter starts partway in rather than at 0, so an id does not hand over its ordinal
        // within the millisecond — and only into the low half, leaving increment headroom. Both
        // halves of that matter: reset-to-zero leaks the ordinal, a full-range seed would make the
        // generator borrow future milliseconds under ordinary load.
        val seeds = List(200) { UuidV7Generator { FIXED_MILLIS }.next().counter() }
        assertTrue(seeds.all { it in 0..0x7FF }, "a seed must leave 2048 increments of headroom, got ${seeds.max()}")
        assertTrue(seeds.toSet().size > 100, "seeds must be random, saw only ${seeds.toSet().size} distinct in 200")
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
