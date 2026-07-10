package dev.ynagai.autograph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
     * Your own event-schema/tracking-plan version, stamped onto every envelope as
     * `schema_version` — distinct from the library's own [Envelope.sdk] version. Null (the
     * default) omits the field. Evolve it only for changes that alter how downstream consumers
     * must interpret an event (e.g. a renamed or repurposed property); purely additive changes
     * don't need a bump, since existing readers already ignore unknown fields.
     */
    public var schemaVersion: String? = null

    /**
     * Checks every `track`/`screen` event against an app-defined tracking-plan contract before
     * it reaches the transport. Null (the default) skips validation entirely. See
     * [strictValidation] for what happens to an event [EventValidator] rejects.
     */
    public var validator: EventValidator? = null

    /**
     * What happens to a `track`/`screen` event [validator] rejects. `false` (the default, meant
     * for release builds) drops the event and logs the reason — a tracking-plan violation should
     * never crash the app. `true` (meant for debug builds) throws immediately instead, so
     * mistakes are caught during development rather than silently dropped in production.
     */
    public var strictValidation: Boolean = false

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
        schemaVersion = config.schemaVersion,
    )
    return AutographTracker(transport, stamper, config.dispatcher, config.validator, config.strictValidation)
}

internal class AutographTracker(
    private val transport: Transport,
    private val stamper: Stamper,
    dispatcher: CoroutineDispatcher,
    private val validator: EventValidator?,
    private val strictValidation: Boolean,
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

    override fun track(name: String, properties: JsonObject, target: String?) {
        if (!isValid(name, properties)) return
        deliver { transport.track(name, withTarget(properties, target), it) }
    }

    override fun screen(name: String, properties: JsonObject) {
        if (!isValid(name, properties)) return
        deliver { transport.screen(name, properties, it) }
    }

    /**
     * Runs [validator] synchronously on the caller's thread, before any dispatch — so
     * [strictValidation] throws with a stack trace pointing at the actual `track`/`screen` call
     * site, not at whatever later picks the event off [scope]. A [validator] that itself throws
     * is treated the same as one that returns a rejection reason — even in non-strict mode, so a
     * buggy validator can't crash a production build merely by running.
     */
    private fun isValid(name: String, properties: JsonObject): Boolean {
        val reason = try {
            validator?.validate(name, properties) ?: return true
        } catch (e: Exception) {
            "validator threw: ${e.message}"
        }
        if (strictValidation) {
            throw IllegalArgumentException("Autograph: invalid event \"$name\": $reason")
        }
        println("Autograph: dropping invalid event \"$name\": $reason")
        return false
    }

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

    // flush and reset must observe the same ordering as delivery: when the core stamps off the
    // caller thread, an event enqueued just before flush()/reset() has not been stamped yet, so
    // running these synchronously would flush before it is delivered, or reset the session out from
    // under it (mis-attributing a pre-reset event to the new session). Route them through the same
    // serial [scope] so they run after any already-enqueued events.
    override fun flush() {
        if (transport.stampsInPipeline) {
            transport.flush()
        } else {
            scope.launch { transport.flush() }
        }
    }

    override fun reset() {
        if (transport.stampsInPipeline) {
            // The core cannot stamp within the transport's own pipeline, so it cannot order a reset
            // there either. Hand the reset to the transport, which sequences the session rotation
            // (EnvelopeSource.reset) inside its pipeline after any already-enqueued events — see
            // EnvelopeSource.reset. Resetting the stamper synchronously here would instead rotate
            // the session out from under events still queued in the pipeline, mis-attributing them.
            transport.reset()
        } else {
            scope.launch {
                stamper.reset()
                transport.reset()
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/** Merges [target] into [properties] under the reserved `"target"` key, or returns [properties] unchanged when null. */
private fun withTarget(properties: JsonObject, target: String?): JsonObject =
    if (target == null) properties else JsonObject(properties + ("target" to JsonPrimitive(target)))
