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

    /** Records a custom event with the given [name] and [properties]. */
    public fun track(name: String, properties: JsonObject = EmptyJsonObject)

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
     * Releases resources held by this tracker (its internal stamping/delivery coroutine scope).
     * Call when a tracker is being discarded and replaced, such as recreating it on logout — not
     * needed for a tracker that lives for the app's lifetime. [track]/[screen]/[identify]/[flush]/
     * [reset] calls made after [close] are dropped, except when the transport stamps in its own
     * pipeline, in which case they still reach the transport directly (this tracker never
     * scheduled them onto the closed scope in the first place).
     */
    public fun close() {}
}

/** An empty [JsonObject], useful as a default for event properties. */
public val EmptyJsonObject: JsonObject = JsonObject(emptyMap())
