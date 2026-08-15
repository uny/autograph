package dev.ynagai.autograph.sample

import dev.ynagai.autograph.asJsonObject
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonElement
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
    private val onTrack: (name: String, properties: JsonObject, target: String?) -> Unit = { _, _, _ -> },
    private val onScreen: (name: String, properties: JsonObject) -> Unit = { _, _ -> },
) : Tracker {
    override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
        sampleLog("track name=$name target=$target properties.asJsonObject()=$properties.asJsonObject()")
        // `target` stays a positional argument: the autocapture pipelines pass it alongside
        // `properties.asJsonObject()`, not merged into it, so a test observing the target reads it here directly.
        // `name` is handed over too — see [appendTrackLog] for why the target alone cannot tell a
        // double report from a single one (#151).
        onTrack(name, properties.asJsonObject(), target)
    }

    override fun screen(name: String, properties: Map<String, JsonElement>) {
        sampleLog("screen name=$name properties.asJsonObject()=$properties.asJsonObject()")
        onScreen(name, properties.asJsonObject())
    }

    override fun identify(userId: String, traits: Map<String, JsonElement>) {
        sampleLog("identify userId=$userId traits.asJsonObject()=$traits.asJsonObject()")
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

/**
 * Appends one `track` entry (`name:target`) to a `|`-delimited log. A last-value label cannot state
 * "fired exactly once" for explicit instrumentation, because the autocapture event that must NOT
 * accompany it carries the very same target — so the ordered log is what makes #151 observable.
 */
internal fun appendTrackLog(current: String, name: String, target: String?): String {
    val entry = "$name:${targetOrNoTarget(target)}"
    return if (current == noEventYet) entry else "$current|$entry"
}

/** Initial value of every observation label, before any event has been surfaced. */
internal const val noEventYet: String = "(none yet)"
