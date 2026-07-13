package dev.ynagai.autograph.test

import dev.ynagai.autograph.Envelope
import dev.ynagai.autograph.EnvelopeSource
import dev.ynagai.autograph.Transport
import kotlinx.serialization.json.JsonObject

/**
 * A [Transport] that records every event in memory instead of sending it anywhere — for unit
 * tests that need to assert "does this code fire the right event with the right properties?"
 * without a real backend or [dev.ynagai.autograph.DebugTransport]'s log-only, non-assertable
 * output.
 *
 * ```kotlin
 * val transport = InMemoryTestTransport()
 * val tracker = Autograph {
 *     transport(transport)
 *     dispatcher = kotlinx.coroutines.Dispatchers.Unconfined // stamp synchronously in tests
 * }
 *
 * tracker.track("Recipe Saved", target = "share_button")
 *
 * transport.assertEventFired("Recipe Saved", properties = mapOf("target" to "share_button"))
 * ```
 */
public class InMemoryTestTransport : Transport {

    public enum class Kind { TRACK, SCREEN, IDENTIFY }

    /** One recorded call. [name] is the event/screen name for [Kind.TRACK]/[Kind.SCREEN], or the user id for [Kind.IDENTIFY]. */
    public data class RecordedEvent(
        val kind: Kind,
        val name: String,
        val properties: JsonObject,
        val envelope: Envelope?,
    )

    private val recorded = mutableListOf<RecordedEvent>()

    /** Every event recorded so far, in the order they were delivered to this transport. */
    public val events: List<RecordedEvent> get() = recorded.toList()

    /** How many times [flush] was called. */
    public var flushCount: Int = 0
        private set

    /** How many times [reset] was called. */
    public var resetCount: Int = 0
        private set

    override fun connect(envelopes: EnvelopeSource) {}

    override fun track(name: String, properties: JsonObject, envelope: Envelope?) {
        recorded += RecordedEvent(Kind.TRACK, name, properties, envelope)
    }

    override fun screen(name: String, properties: JsonObject, envelope: Envelope?) {
        recorded += RecordedEvent(Kind.SCREEN, name, properties, envelope)
    }

    override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) {
        recorded += RecordedEvent(Kind.IDENTIFY, userId, traits, envelope)
    }

    override fun flush() {
        flushCount++
    }

    override fun reset() {
        resetCount++
    }

    /** Discards every recorded event — useful between test cases sharing one transport/tracker. */
    public fun clear() {
        recorded.clear()
    }
}
