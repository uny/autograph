package dev.ynagai.autograph.sample

import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A minimal [Tracker] that just logs every call — this sample demonstrates `autograph-compose`'s
 * own API (`AutographProvider`, `Modifier.trackClick`/`trackImpression`, autocapture), not a real
 * transport, so it deliberately has no Segment/network dependency.
 *
 * [onTrack] and [onScreen] additionally surface every event on-screen for the `sample-iosUITests`
 * XCUITest suite, which can't inspect Kotlin state directly. [onTrack] hands over the whole
 * [JsonObject] properties — not just `target` — so a test can observe the `screen`, `section`, and
 * scope (e.g. `article_id`) an autocaptured tap was attributed with, the exact context that the
 * ambient [dev.ynagai.autograph.context.ScopeStack] enriches an event with. [onScreen] surfaces
 * `Screen Viewed` events so the suite can assert a screen was reported (and, via its
 * `previous_screen`, in the right order) — the channel #65's native screen capture reports through.
 */
internal class LoggingTracker(
    private val onTrack: (properties: JsonObject, target: String?) -> Unit = { _, _ -> },
    private val onScreen: (name: String, properties: JsonObject) -> Unit = { _, _ -> },
) : Tracker {
    override fun track(name: String, properties: JsonObject, target: String?) {
        sampleLog("track name=$name target=$target properties=$properties")
        // `target` stays a positional argument: the autocapture pipelines pass it alongside
        // `properties`, not merged into it, so a test observing the target reads it here directly.
        onTrack(properties, target)
    }

    override fun screen(name: String, properties: JsonObject) {
        sampleLog("screen name=$name properties=$properties")
        onScreen(name, properties)
    }

    override fun identify(userId: String, traits: JsonObject) {
        sampleLog("identify userId=$userId traits=$traits")
    }
}

/** The `target` an autocaptured event resolved to, or `"(no target)"` — mirrors the pre-widening label. */
internal fun targetOrNoTarget(target: String?): String = target ?: "(no target)"

/** A reserved-key string value, or [none] when the key is absent or JSON-null. */
internal fun JsonObject.reservedOrNone(key: String, none: String = "(none)"): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: none

/**
 * Appends one `Screen Viewed` entry (`name:previous_screen`) to a `|`-delimited log. The ordered log,
 * rather than a last-value label, is what lets a test see a screen was reported exactly once and in
 * sequence — the shape #65's de-dup (a cancelled interactive pop must not re-emit) has to preserve.
 */
internal fun appendScreenLog(current: String, name: String, previousScreen: String): String {
    val entry = "$name:$previousScreen"
    return if (current == noEventYet) entry else "$current|$entry"
}

/** Initial value of every observation label, before any event has been surfaced. */
internal const val noEventYet: String = "(none yet)"
