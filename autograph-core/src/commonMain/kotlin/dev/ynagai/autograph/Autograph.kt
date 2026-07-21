package dev.ynagai.autograph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
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
     * Sink for the library's own diagnostics — a failed delivery, or an event dropped for failing
     * [validator]. Defaults to printing to the console (the historical behavior); set your own to
     * route them into your app's logging framework (Logcat, `os_log`, Timber) or to silence them.
     * See [AutographLogger]. This routes only the diagnostics of a *constructed tracker*; a
     * pre-provider warning (compose's `MissingTracker`) or a storage-fallback notice logged before a
     * tracker exists still goes to the console, since there is no configured logger yet at that point.
     */
    public var logger: AutographLogger = DefaultAutographLogger

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
    return AutographTracker(transport, stamper, config.dispatcher, config.validator, config.strictValidation, config.clock, config.logger)
}

internal class AutographTracker(
    private val transport: Transport,
    private val stamper: Stamper,
    dispatcher: CoroutineDispatcher,
    private val validator: EventValidator?,
    private val strictValidation: Boolean,
    private val clock: () -> Long,
    private val logger: AutographLogger,
) : Tracker {

    // A failed analytics delivery must never crash the app, and one failure must not tear down the
    // scope for the next event — hence SupervisorJob + a swallowing handler.
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
            report("Autograph: event delivery failed: ${e.message}")
        },
    )

    /**
     * Routes a diagnostic to [logger], swallowing anything it throws. A buggy logger must not do what
     * a buggy [EventValidator] can't — escalate a swallowed delivery failure or a dropped event into
     * an app crash. This runs from two threads (the caller's, when an event is dropped; the dispatcher,
     * when delivery fails), so the swallow keeps the never-crash contract on both.
     */
    private fun report(message: String) {
        try {
            logger.log(message)
        } catch (_: Exception) {
            // The diagnostic sink is itself what failed; there is nowhere safe left to report that, so
            // the message is lost rather than turned into a crash.
        }
    }

    /**
     * Set at the very start of [close], before the drain, so nothing new joins the set of work being
     * drained — otherwise a `track` racing the shutdown could enqueue after the children were
     * snapshotted and be cancelled anyway, which is the loss [close] exists to prevent.
     */
    @Volatile
    private var closed = false

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
     *
     * The `event_timestamp` is read here, on the caller's thread at call time, and handed to the
     * stamper — so it reflects when the app fired the event, not when the dispatcher later drained
     * and stamped it (which can lag behind under backpressure).
     */
    private fun deliver(send: (Envelope?) -> Unit) {
        if (transport.stampsInPipeline) {
            send(null)
        } else {
            if (closed) return
            val eventTimestampMillis = clock()
            scope.launch { send(stamper.stamp(eventTimestampMillis)) }
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
        report("Autograph: dropping invalid event \"$name\": $reason")
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
            if (closed) return
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
            if (closed) return
            scope.launch {
                stamper.reset()
                transport.reset()
            }
        }
    }

    /**
     * Drains before releasing: stop accepting new work, wait for everything already enqueued to be
     * stamped and handed to the transport, [Transport.flush] it, and only then cancel the scope.
     *
     * The old implementation cancelled outright, which silently dropped every enqueued-but-unstamped
     * event — a self-inconsistency for a library whose headline guarantee is that ordering information
     * is never silently lost. It is rare on mobile (a tracker usually lives for the process) but real
     * on `jvm()`/desktop, where a graceful shutdown is expected to deliver what it accepted.
     *
     * Waiting on the scope's *children* rather than enqueueing one last coroutine is deliberate: the
     * latter only orders correctly while [AutographConfig.dispatcher] stays single-slot, and the
     * dispatcher is caller-configurable. Joining the children drains whatever is outstanding under any
     * dispatcher.
     *
     * Bounded by [CLOSE_DRAIN_TIMEOUT_MILLIS] — see [drainBlocking] for why a bound, and for the one
     * configuration (closing from the tracker's own single-threaded dispatcher) that starves the drain.
     * Idempotent.
     */
    override fun close() {
        if (closed) return
        closed = true
        if (transport.stampsInPipeline) {
            // Nothing was ever scheduled onto the scope, so there is nothing of ours to drain; the
            // transport owns its own queue and flush is the only thing we can ask of it.
            transport.flush()
        } else {
            drainBlocking(CLOSE_DRAIN_TIMEOUT_MILLIS) {
                // Snapshot first: joining a live sequence would also wait on anything added while we
                // wait, and `closed` has already stopped this tracker adding more.
                scope.coroutineContext.job.children.toList().joinAll()
                transport.flush()
            }
        }
        scope.cancel()
    }
}

/**
 * How long [Tracker.close] waits for already-enqueued events to reach the transport.
 *
 * Sized for a shutdown path a user is waiting on: long enough for a queue of stamped events and one
 * transport flush (both local operations — the transport's own network delivery is its business, not
 * something `close` can await), short enough that a wedged transport cannot hold an app's teardown.
 */
private const val CLOSE_DRAIN_TIMEOUT_MILLIS = 5_000L

/** Merges [target] into [properties] under the reserved `"target"` key, or returns [properties] unchanged when null. */
private fun withTarget(properties: JsonObject, target: String?): JsonObject =
    if (target == null) properties else JsonObject(properties + ("target" to JsonPrimitive(target)))
