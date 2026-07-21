package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject

/**
 * The main entry point for recording analytics events.
 *
 * Obtain an instance via the [Autograph] builder function and provide it to your UI
 * (e.g. through `AutographProvider` from the `autograph-compose` module).
 *
 * [track], [screen], and [identify] are fire-and-forget and safe to call from any thread,
 * including the main thread: stamping and its disk persistence run on an internal serial
 * dispatcher, so they never block the caller. Events are still stamped in call order.
 */
public interface Tracker {

    /**
     * Records a custom event with the given [name] and [properties].
     *
     * [target] identifies which UI element or entry point triggered the event (e.g. the same
     * "select_item" fired from a map pin vs. a list row vs. history), as a stable, library-
     * managed field rather than an ad-hoc `properties` key that every adopter names differently.
     * When set, it is merged into [properties] under the reserved key `"target"` — an explicit
     * `"target"` entry already in [properties] is overwritten. Omit for events with no
     * meaningful originating element (e.g. lifecycle events).
     */
    public fun track(name: String, properties: JsonObject = EmptyJsonObject, target: String? = null)

    /** Records a screen view. */
    public fun screen(name: String, properties: JsonObject = EmptyJsonObject)

    /** Associates the current user with [userId] and optional [traits]. */
    public fun identify(userId: String, traits: JsonObject = EmptyJsonObject)

    /**
     * Signals that the app moved to the foreground. Drives session-timeout bookkeeping.
     * Wired automatically when using `AutographProvider` from `autograph-compose`.
     */
    public fun notifyForeground() {}

    /** Signals that the app moved to the background. See [notifyForeground]. */
    public fun notifyBackground() {}

    /** Asks the underlying transport to send any queued events now. */
    public fun flush() {}

    /** Clears user identity and session state, e.g. on logout. */
    public fun reset() {}

    /**
     * Drains, then releases the resources held by this tracker (its internal stamping/delivery
     * coroutine scope). Call when a tracker is being discarded and replaced, such as recreating it on
     * logout — not needed for a tracker that lives for the app's lifetime.
     *
     * **Blocks until everything already accepted has been stamped and handed to the transport, then
     * flushes it** — up to an internal timeout, after which it gives up and releases anyway rather than
     * hold a shutdown open indefinitely. Events accepted before [close] are therefore delivered, not
     * discarded; what the transport then does with them (network delivery, its own retry queue) is the
     * transport's business and is not awaited beyond [flush].
     *
     * Do not point `AutographConfig.dispatcher` at the thread that calls [close]: blocking a
     * single-threaded dispatcher from inside itself starves the drain, which then delivers nothing and
     * returns at the timeout.
     *
     * Idempotent. [track]/[screen]/[identify]/[flush]/[reset] calls made after [close] are dropped,
     * except when the transport stamps in its own pipeline, in which case they still reach the
     * transport directly (this tracker never scheduled them onto its scope in the first place).
     * [notifyForeground] and [notifyBackground] are unaffected either way: they update session state
     * synchronously and never went through the scope, so they keep working on a closed tracker.
     */
    public fun close() {}
}

/** An empty [JsonObject], useful as a default for event properties. */
public val EmptyJsonObject: JsonObject = JsonObject(emptyMap())
