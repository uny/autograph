package dev.ynagai.autograph

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.uuid.Uuid

/**
 * Generates UUIDv7 (RFC 9562) ids that sort in generation order.
 *
 * The stdlib has `Uuid.generateV7()`, but only from Kotlin 2.4 — a floor that locks out every
 * consumer needing KSP, which has no 2.4 release (see #205). One 12-bit counter is the whole cost
 * of not requiring it.
 *
 * A plain "millisecond timestamp plus random bits" layout is *not* enough: ids minted inside the
 * same millisecond would order randomly, and `event_id` is documented as time-ordered. This
 * follows RFC 9562 §6.2 method 3 instead — a monotonic counter in `rand_a` breaks ties within a
 * millisecond:
 *
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                        unix_ts_ms (48)                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |         unix_ts_ms   |  ver (7)  |     counter (rand_a, 12)    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |var|                       rand_b (62)                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 *
 * On each new millisecond the counter is re-seeded randomly rather than reset to zero, into the low
 * half of its range so there is room left to increment. Ordering does not depend on the seed:
 * within a millisecond the counter only ever increases, and across milliseconds the timestamp does.
 * The seed also stops an id from advertising how many preceded it in that millisecond — but only
 * over the seedable half: a `rand_a` above [SEED_MASK] still proves at least `rand_a - SEED_MASK`
 * ids came before it, so this blurs the count rather than hiding it.
 *
 * Randomness comes from [Uuid.random], not `kotlin.random.Random`, so `rand_b` and the counter
 * seed carry the same cryptographic quality the stdlib gives [EventId.UuidV4] on every platform.
 *
 * Each *instance* owns its counter state and its clock, so a caller with an injected clock (see
 * [Stamper]) cannot have its ids perturbed by another caller reading wall time. Note what that does
 * and does not buy: [EventId.UuidV7] deliberately shares one process-wide instance across every
 * tracker, which is what makes `event_id` monotonic process-wide, and which equally means the
 * `rand_a` gap between two ids counts the ids minted in between for *all* destinations, not just
 * the one holding them.
 *
 * @param nowMillis the clock, in Unix epoch milliseconds.
 */
internal class UuidV7Generator(private val nowMillis: () -> Long) {

    private val lock = SynchronizedObject()

    /** The millisecond the last id was stamped with — never allowed to move backwards. */
    private var lastMillis = Long.MIN_VALUE

    /** The `rand_a` counter that orders ids sharing [lastMillis]. */
    private var counter = 0

    fun next(): Uuid = synchronized(lock) {
        val now = usableMillis(nowMillis())
        // A clock that jumps backwards must not produce a regressing id, so hold the timestamp and
        // keep counting instead: correctness here is ordering, not tracking wall time exactly.
        if (now > lastMillis) {
            lastMillis = now
            counter = seedCounter()
        } else if (counter >= COUNTER_MASK) {
            // Saturated — 4096 ids inside one millisecond. Borrow from the next millisecond rather
            // than repeat a (timestamp, counter) pair, unless the field itself is exhausted: at
            // MAX_TIMESTAMP (year 10889) there is nothing left to borrow. Ordering is then lost
            // for as long as the clock stays there — and only ordering; ids remain unique, which is
            // rand_b's job rather than the counter's. Getting here takes a clock that genuinely
            // reads the ceiling: a merely out-of-range reading cannot, see [usableMillis].
            if (lastMillis < MAX_TIMESTAMP) {
                lastMillis += 1
                counter = seedCounter()
            }
        } else {
            counter += 1
        }
        buildUuid(lastMillis, counter)
    }

    /**
     * Maps a raw clock reading onto the 48 bits `unix_ts_ms` actually has — resolved *before* the
     * comparison in [next] rather than at encoding time, since comparing raw values would let -1
     * and 0 count as a rising clock and encode as `0xffffffffffff` then `0x000000000000`, an id
     * sorting before every id already issued.
     *
     * A reading past the ceiling is treated as **unusable rather than clamped down to it**, and
     * that distinction is the whole point of this function. Adopting MAX_TIMESTAMP would pin
     * `lastMillis` at the ceiling for the rest of the process: every later reading — a corrected
     * one included — then fails `now > lastMillis`, so the counter saturates and the
     * exhausted-field branch has nothing left to borrow. One garbage reading would cost ordering
     * permanently, and a wrong-unit clock handing over micro- or nanoseconds is a realistic way to
     * produce exactly one. Holding the last good millisecond instead sends the reading down the
     * same counter path a backwards jump takes, so the cost lasts only as long as the bad clock.
     */
    private fun usableMillis(raw: Long): Long = when {
        raw < 0L -> 0L
        raw > MAX_TIMESTAMP -> maxOf(lastMillis, 0L)
        else -> raw
    }

    private companion object {
        /** The largest value `unix_ts_ms`'s 48 bits can hold. */
        const val MAX_TIMESTAMP = (1L shl 48) - 1
        const val COUNTER_MASK = 0xFFF
        /** Seeds into the low half of the counter's range, leaving 2048 increments of headroom. */
        const val SEED_MASK = 0x7FF
        const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL

        fun seedCounter(): Int = (randomBits() and SEED_MASK.toLong()).toInt()

        fun buildUuid(millis: Long, counter: Int): Uuid {
            val msb = (millis shl 16) or (7L shl 12) or counter.toLong()
            // Variant `10` in the two most significant bits; the remaining 62 are rand_b.
            val lsb = (1L shl 63) or (randomBits() and RAND_B_MASK)
            return Uuid.fromLongs(msb, lsb)
        }

        fun randomBits(): Long = Uuid.random().toLongs { _, leastSignificantBits ->
            leastSignificantBits
        }
    }
}
