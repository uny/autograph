package dev.ynagai.autograph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /**
     * The dispatcher on which events are stamped and persisted when the transport does not stamp
     * in its own pipeline (e.g. the iOS Segment bridge, or a custom transport). It must be
     * **single-threaded / serial** so sequence numbers keep their call order; the default is a
     * single-slot view over [Dispatchers.Default] that owns no thread of its own. Override to
     * integrate with your own threading, or set [Dispatchers.Unconfined] in tests to stamp
     * synchronously.
     */
    public var dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

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
    return AutographTracker(transport, stamper, config.dispatcher)
}

internal class AutographTracker(
    private val transport: Transport,
    private val stamper: Stamper,
    dispatcher: CoroutineDispatcher,
) : Tracker {

    // A failed analytics delivery must never crash the app, and one failure must not tear down the
    // scope for the next event — hence SupervisorJob + a swallowing handler.
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
            println("Autograph: event delivery failed: ${e.message}")
        },
    )

    init {
        transport.connect(stamper)
    }

    /**
     * When the transport stamps inside its own pipeline (e.g. Segment on Android), it already runs
     * off the caller's thread, so we hand off synchronously with no envelope. Otherwise the core
     * stamps: stamping persists the sequence high-water mark to disk (under [SeqPersistence.EveryEvent],
     * every event), so we do it on the serial [scope] to keep that write off the caller's thread —
     * which is frequently the main thread (a Compose `LaunchedEffect`). The dispatcher is single-slot,
     * so events are still stamped in call order.
     */
    private fun deliver(send: (Envelope?) -> Unit) {
        if (transport.stampsInPipeline) {
            send(null)
        } else {
            scope.launch { send(stamper.stamp()) }
        }
    }

    override fun track(name: String, properties: JsonObject): Unit =
        deliver { transport.track(name, properties, it) }

    override fun screen(name: String, properties: JsonObject): Unit =
        deliver { transport.screen(name, properties, it) }

    override fun identify(userId: String, traits: JsonObject): Unit =
        deliver { transport.identify(userId, traits, it) }

    // Lifecycle/control calls are infrequent (not per-event) and stay synchronous. notifyBackground
    // in particular is a durability checkpoint that must persist the session before the app is
    // suspended, so it must not be deferred onto a background dispatcher.
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
