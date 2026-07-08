package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/** Configuration for the [Autograph] builder. */
public class AutographConfig internal constructor() {

    /** Strategy for per-event ids. Defaults to time-ordered, monotonic UUIDv7. */
    public var eventId: EventIdGenerator = EventId.UuidV7

    /** Which sequence numbers to stamp. Defaults to a per-session counter. */
    public var sequence: SequenceMode = SequenceMode.PerSession

    /** How often sequence counters are persisted. */
    public var persistence: SeqPersistence = SeqPersistence.EveryEvent

    /** Session timeout semantics. */
    public var session: SessionConfig = SessionConfig()

    /** Key-value storage for counters and session state. Defaults to the platform store. */
    public var store: SeqStore? = null

    internal var transport: Transport? = null
    internal var clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    /** Sets the transport that delivers events, e.g. `SegmentTransport` from `autograph-segment`. */
    public fun transport(transport: Transport) {
        this.transport = transport
    }
}

/**
 * Creates a [Tracker].
 *
 * ```kotlin
 * val tracker = Autograph {
 *     transport(SegmentTransport(analytics))
 *     eventId = EventId.UuidV7
 *     sequence = SequenceMode.PerSession
 * }
 * ```
 */
public fun Autograph(configure: AutographConfig.() -> Unit): Tracker {
    val config = AutographConfig().apply(configure)
    val transport = requireNotNull(config.transport) {
        "Autograph requires a transport. Call transport(...) inside the Autograph { } block."
    }
    val stamper = Stamper(
        idGen = config.eventId,
        mode = config.sequence,
        persistence = config.persistence,
        sessionConfig = config.session,
        store = config.store ?: platformSeqStore(),
        clock = config.clock,
    )
    return AutographTracker(transport, stamper)
}

internal class AutographTracker(
    private val transport: Transport,
    private val stamper: Stamper,
) : Tracker {

    init {
        transport.connect(stamper)
    }

    private fun envelopeOrNull(): Envelope? =
        if (transport.stampsInPipeline) null else stamper.stamp()

    override fun track(name: String, properties: JsonObject) {
        transport.track(name, properties, envelopeOrNull())
    }

    override fun screen(name: String, properties: JsonObject) {
        transport.screen(name, properties, envelopeOrNull())
    }

    override fun identify(userId: String, traits: JsonObject) {
        transport.identify(userId, traits, envelopeOrNull())
    }

    override fun notifyForeground() {
        stamper.notifyForeground()
    }

    override fun notifyBackground() {
        stamper.notifyBackground()
    }

    override fun flush() {
        transport.flush()
    }

    override fun reset() {
        stamper.reset()
        transport.reset()
    }
}
