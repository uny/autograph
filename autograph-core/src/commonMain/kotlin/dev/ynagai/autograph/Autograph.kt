package dev.ynagai.autograph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
     * Properties merged into every `track`/`screen` event before [validator] runs, at the lowest
     * precedence — see [DefaultProperties]. Keep the instance you pass here to change them later. Null
     * (the default) adds nothing.
     */
    public var defaultProperties: DefaultProperties? = null

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
     * synchronously. A dispatcher that runs work inline (`Unconfined`, or any whose
     * `isDispatchNeeded` is false) also runs delivery inside the tracker's admission lock, serializing
     * callers behind the transport: fine for tests, not for production.
     */
    public var dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    internal var transport: Transport? = null
    internal var clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
    internal var closeDrainTimeoutMillis: Long = CLOSE_DRAIN_TIMEOUT_MILLIS

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
    return AutographTracker(
        transport, stamper, config.dispatcher, config.validator, config.strictValidation, config.defaultProperties,
        config.clock, config.logger, config.closeDrainTimeoutMillis,
    )
}

internal class AutographTracker(
    private val transport: Transport,
    private val stamper: Stamper,
    dispatcher: CoroutineDispatcher,
    private val validator: EventValidator?,
    private val strictValidation: Boolean,
    private val defaultProperties: DefaultProperties?,
    private val clock: () -> Long,
    private val logger: AutographLogger,
    private val closeDrainTimeoutMillis: Long,
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
     * Guards the admission cutoff: [closed], [inFlight], and the launch onto [scope] all change under
     * it, so a call is accepted if and only if it takes the lock before [close] does. Checking [closed]
     * and launching as two unguarded steps let a `track` racing the shutdown pass the check, then
     * launch after [close] had snapshotted the children — outside the drain, and cancelled with the
     * scope: the very loss [close] exists to prevent.
     */
    private val lock = SynchronizedObject()

    private var closed = false

    /**
     * Pipeline-mode calls accepted but still inside the transport. They run synchronously on the
     * caller's thread, outside [lock], so [close] waits for them before flushing — otherwise its flush
     * could overtake an accepted call still on its way into the transport.
     */
    private var inFlight = 0

    /** How many of [inFlight] are on the current thread — calls a reentrant [close] is nested inside. */
    private val ownInFlight = ThreadLocalCount()

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
            // Pipeline delivery is synchronous on the caller's thread — no [scope], so the
            // CoroutineExceptionHandler above never sees it. Mirror its swallow-and-[report] here so a
            // throwing pipeline transport honors the same "a failed delivery must never crash the app"
            // contract, and its failure still reaches [logger] instead of propagating into `track`.
            admitInPipeline {
                try {
                    send(null)
                } catch (e: Exception) {
                    report("Autograph: event delivery failed: ${e.message}")
                }
            }
        } else {
            synchronized(lock) {
                if (closed) return
                val eventTimestampMillis = clock()
                scope.launch { send(stamper.stamp(eventTimestampMillis)) }
            }
        }
    }

    /**
     * Runs [call] on the caller's thread if this tracker is still open, counted in [inFlight] so
     * [close] can wait for it. The call itself runs outside [lock]: holding a lock across a transport
     * we don't own would serialize every caller behind it, and deadlock one that calls back in.
     */
    private inline fun admitInPipeline(call: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            inFlight++
        }
        ownInFlight.set(ownInFlight.get() + 1)
        try {
            call()
        } finally {
            ownInFlight.set(ownInFlight.get() - 1)
            synchronized(lock) { inFlight-- }
        }
    }

    /** Launches [block] onto [scope] if this tracker is still open, atomically with respect to [close]. */
    private fun launchIfOpen(block: suspend () -> Unit) {
        synchronized(lock) {
            if (closed) return
            scope.launch { block() }
        }
    }

    override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
        val props = withDefaults(properties)
        if (!isValid(name, props)) return
        deliver { transport.track(name, withTarget(props, target), it) }
    }

    override fun screen(name: String, properties: Map<String, JsonElement>) {
        val props = withDefaults(properties)
        if (!isValid(name, props)) return
        deliver { transport.screen(name, props, it) }
    }

    /**
     * [properties] with [defaultProperties] snapshotted beneath them — on the caller's thread, before
     * validation, so the validator judges the event the transport will receive, and a default changed
     * after this call cannot reach it (#253). A call-site property wins a key clash.
     */
    private fun withDefaults(properties: Map<String, JsonElement>): JsonObject {
        val props = properties.asJsonObject()
        val defaults = defaultProperties?.properties
        if (defaults.isNullOrEmpty()) return props
        return JsonObject(defaults + props)
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

    // Narrowed here rather than inside [deliver]'s lambda: [traits] is the `Map` interface, so it
    // may be a map the caller still owns and mutates, and the lambda runs later on the dispatcher.
    // track/screen snapshot eagerly above for the same reason.
    override fun identify(userId: String, traits: Map<String, JsonElement>) {
        val snapshot = traits.asJsonObject()
        deliver { transport.identify(userId, snapshot, it) }
    }

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
            admitInPipeline { transport.flush() }
        } else {
            launchIfOpen { transport.flush() }
        }
    }

    override fun reset() {
        if (transport.stampsInPipeline) {
            // The core cannot stamp within the transport's own pipeline, so it cannot order a reset
            // there either. Hand the reset to the transport, which sequences the session rotation
            // (EnvelopeSource.reset) inside its pipeline after any already-enqueued events — see
            // EnvelopeSource.reset. Resetting the stamper synchronously here would instead rotate
            // the session out from under events still queued in the pipeline, mis-attributing them.
            admitInPipeline { transport.reset() }
        } else {
            launchIfOpen {
                stamper.reset()
                transport.reset()
            }
        }
    }

    /**
     * Drains before releasing: stop accepting new work, wait for everything already accepted to be
     * stamped and handed to the transport, [Transport.flush] it, and only then cancel the scope.
     *
     * The cutoff is one operation in both transport modes: under [lock], [closed] is set and the work
     * to drain is fixed — the scope's children when the core stamps, the calls still inside the
     * transport ([inFlight]) when it stamps in its own pipeline. The wait itself happens outside the
     * lock, so a `track` racing the shutdown is refused at once rather than blocked for the drain.
     * A pipeline transport that calls [close] from inside one of this tracker's own calls is not waited
     * for — it cannot finish until [close] returns — so that call's event reaches the transport after
     * the flush.
     *
     * Admission stops *through this tracker* only. A pipeline transport's vendor client is not ours:
     * whatever the app or the vendor SDK sends through it directly is still delivered, and stamped.
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
     * Bounded by [CLOSE_DRAIN_TIMEOUT_MILLIS] by default — see [drainBlocking] for why a bound, and for the one
     * configuration (closing from the tracker's own single-threaded dispatcher) that starves the drain.
     * A drain cut short by the bound is reported through the logger rather than returning silently.
     * Idempotent.
     */
    override fun close() {
        val accepted = synchronized(lock) {
            if (closed) return
            closed = true
            // Snapshot under the lock: every launch that won the race is already a child, and none can
            // be added after this point.
            scope.coroutineContext.job.children.toList()
        }
        val drained = if (transport.stampsInPipeline) {
            // Nothing of ours is queued; wait only for accepted calls still entering the transport,
            // which owns its own queue — flush is the only thing we can ask of it. A close() made from
            // inside one of those calls cannot wait for the calls it is nested in, so it waits for the
            // other threads' only. The flush runs even if the wait timed out: the transport's queue is
            // already full of accepted events, and one stuck call must not cost them their flush.
            val nested = ownInFlight.get()
            drainBlocking(closeDrainTimeoutMillis) {
                while (synchronized(lock) { inFlight } > nested) delay(1)
            }.also { transport.flush() }
        } else {
            drainBlocking(closeDrainTimeoutMillis) {
                accepted.joinAll()
                transport.flush()
            }
        }
        if (!drained) {
            report("Autograph: close() gave up after ${closeDrainTimeoutMillis}ms; events it accepted may not have reached the transport")
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
