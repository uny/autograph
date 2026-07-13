package dev.ynagai.autograph.sample

import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject

/**
 * A minimal [Tracker] that just logs every call — this sample demonstrates `autograph-compose`'s
 * own API (`AutographProvider`, `Modifier.trackClick`/`trackImpression`, autocapture), not a real
 * transport, so it deliberately has no Segment/network dependency.
 *
 * [onTrack] additionally surfaces every `track` call's `target` on-screen (see `App.kt`'s
 * `last_event_label`) — this is what the `sample-iosUITests` XCUITest suite reads to assert the
 * iOS resolver (`ElementResolver.ios.kt`) attributed a tap to the right element, since it can't
 * inspect Kotlin state directly.
 */
internal class LoggingTracker(private val onTrack: (target: String?) -> Unit = {}) : Tracker {
    override fun track(name: String, properties: JsonObject, target: String?) {
        sampleLog("track name=$name target=$target properties=$properties")
        onTrack(target)
    }

    override fun screen(name: String, properties: JsonObject) {
        sampleLog("screen name=$name properties=$properties")
    }

    override fun identify(userId: String, traits: JsonObject) {
        sampleLog("identify userId=$userId traits=$traits")
    }
}
