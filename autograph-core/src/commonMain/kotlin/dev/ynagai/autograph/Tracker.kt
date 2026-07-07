package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject

/**
 * The main entry point for recording analytics events.
 *
 * Obtain an instance via the [Autograph] builder function and provide it to your UI
 * (e.g. through `AutographProvider` from the `autograph-compose` module).
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
}

/** An empty [JsonObject], useful as a default for event properties. */
public val EmptyJsonObject: JsonObject = JsonObject(emptyMap())
