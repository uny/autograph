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
 * On each new millisecond the counter is re-seeded randomly rather than reset to zero, so an id
 * does not advertise how many preceded it — but only into the low half of its range, leaving room
 * to increment. Ordering does not depend on the seed: within a millisecond the counter only ever
 * increases, and across milliseconds the timestamp does.
 *
 * Randomness comes from [Uuid.random], not `kotlin.random.Random`, so `rand_b` and the counter
 * seed carry the same cryptographic quality the stdlib gives [EventId.UuidV4] on every platform.
 *
 * Each instance owns its counter state and its clock, so a caller with an injected clock (see
 * [Stamper]) cannot have its ids perturbed by another caller reading wall time.
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
        val now = nowMillis()
        // A clock that jumps backwards must not produce a regressing id, so hold the timestamp and
        // keep counting instead: correctness here is ordering, not tracking wall time exactly.
        if (now > lastMillis) {
            lastMillis = now
            counter = seedCounter()
        } else if (counter >= COUNTER_MASK) {
            // Saturated — 4096 ids inside one millisecond. Borrow from the next millisecond rather
            // than repeat a (timestamp, counter) pair.
            lastMillis += 1
            counter = seedCounter()
        } else {
            counter += 1
        }
        buildUuid(lastMillis, counter)
    }

    private companion object {
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
