package dev.ynagai.autograph.test

import dev.ynagai.autograph.AutographInternalApi
import dev.ynagai.autograph.Envelope
import dev.ynagai.autograph.EnvelopeSource
import dev.ynagai.autograph.createEnvelope

/**
 * Builds an [Envelope] with exactly the field values you ask for, for tests that need to assert on a
 * *known* envelope rather than whatever a real tracker happens to stamp.
 *
 * Every parameter defaults, so a test names only the fields it asserts on:
 *
 * ```kotlin
 * val envelope = testEnvelope(seq = 7, sessionId = "sess-1")
 * ```
 *
 * The production path is [EnvelopeSource.stamp] — the tracker owns event-id uniqueness, session
 * rotation, and sequence monotonicity, and an envelope assembled by hand has none of them. That is
 * why [Envelope]'s own constructor is `internal`, and why this factory lives in `autograph-test`,
 * which carries no ABI guarantee and may therefore gain parameters as the envelope grows.
 *
 * To test *stamping itself*, build a real tracker over an [InMemoryTestTransport] and
 * `InMemorySeqStore` instead; this factory deliberately proves nothing about the stamper.
 */
@OptIn(AutographInternalApi::class)
public fun testEnvelope(
    eventId: String = "test-event-id",
    sessionId: String = "test-session",
    sessionStartEpochMillis: Long = 0L,
    seq: Long? = null,
    globalSeq: Long? = null,
    sdk: String = "autograph/test",
    eventTimestamp: String = "2026-01-01T00:00:00.000Z",
    schemaVersion: String? = null,
): Envelope = createEnvelope(
    eventId = eventId,
    sessionId = sessionId,
    sessionStartEpochMillis = sessionStartEpochMillis,
    seq = seq,
    globalSeq = globalSeq,
    sdk = sdk,
    eventTimestamp = eventTimestamp,
    schemaVersion = schemaVersion,
)
