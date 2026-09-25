package dev.ynagai.autograph

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Properties the tracker adds to **every** `track` and `screen` event — a tenant, an install id, an
 * experiment assignment: context that belongs to the app rather than to any screen or call site.
 *
 * Create one, hand it to [AutographConfig.defaultProperties], and keep the reference to change it
 * later:
 *
 * ```kotlin
 * val defaults = DefaultProperties()
 * val tracker = Autograph {
 *     transport(SegmentTransport(analytics))
 *     defaultProperties = defaults
 * }
 * defaults.set("tenant", JsonPrimitive(tenantId))
 * // An experiment assignment that arrives late:
 * defaults.set("checkout_variant", JsonPrimitive("b"))
 * ```
 *
 * **Merged before validation.** The tracker snapshots these on the caller's thread when it admits the
 * event and merges them in *before* [AutographConfig.validator] runs, so a tracking plan may require a
 * key that only a default supplies. That is what a `Transport` wrapper cannot do: the validator has
 * already run by the time an event reaches the transport (#253). An event reflects the defaults as
 * they were when `track`/`screen` was called, not when it is later stamped or delivered.
 *
 * **Lowest precedence.** Every other source of a property wins a key clash:
 *
 *     defaults < lexical / resolved scope < explicit call-site properties < reserved instrumentation fields
 *
 * The tracker itself sees only two of those layers — the `properties` it was called with, which
 * `autograph-compose` and the native captures have already merged a scope into, and the reserved
 * `target` it adds after validation — so what it enforces is `defaults < properties < target`, and
 * the middle two tiers hold because every emitter in this library merges its scope under the
 * call-site properties before calling the tracker.
 *
 * **Not on `identify`.** Traits describe the user, not the event, so defaults are not merged into
 * them.
 *
 * **Reaches every event the tracker records** — explicit `track`/`screen` calls from any code, and every
 * autocaptured tap and `Screen Viewed`, since those reach the transport through the same tracker. It
 * is applied by the tracker the [Autograph] builder returns: a [Tracker] you implement yourself, such
 * as a test fake, does not apply it.
 *
 * **Threading.** Safe to read and change from any thread. Each call is atomic, and an event sees either
 * all of a call's change or none of it; two separate calls are two separate changes, so use
 * [replaceAll] when several keys must change together.
 *
 * Nothing clears it for you: [Tracker.reset] leaves it untouched. On logout, [clear] it (or remove the
 * keys that described the old user's context) alongside the reset.
 */
public class DefaultProperties {

    private val lock = SynchronizedObject()

    private var current: JsonObject = EmptyJsonObject

    /** The current defaults, as one consistent snapshot. */
    public val properties: JsonObject
        get() = synchronized(lock) { current }

    /** Sets [key] to [value], replacing any value it already had. */
    public fun set(key: String, value: JsonElement) {
        synchronized(lock) { current = JsonObject(current + (key to value)) }
    }

    /** Removes [key]. Removing a key that is not set does nothing. */
    public fun remove(key: String) {
        synchronized(lock) {
            if (key in current) current = JsonObject(current - key)
        }
    }

    /**
     * Replaces every default with [properties] in one step, so no event sees a mix of the old set and
     * the new one — a tenant switch that changes several keys at once, for example.
     */
    public fun replaceAll(properties: Map<String, JsonElement>) {
        // Copied, not narrowed: the argument may be a map the caller still owns and mutates.
        val snapshot = JsonObject(properties.toMap())
        synchronized(lock) { current = snapshot }
    }

    /** Removes every default. */
    public fun clear() {
        synchronized(lock) { current = EmptyJsonObject }
    }
}
