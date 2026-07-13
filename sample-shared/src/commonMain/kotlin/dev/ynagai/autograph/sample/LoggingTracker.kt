package dev.ynagai.autograph.sample

import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject

/**
 * A minimal [Tracker] that just logs every call — this sample demonstrates `autograph-compose`'s
 * own API (`AutographProvider`, `Modifier.trackClick`/`trackImpression`, autocapture), not a real
 * transport, so it deliberately has no Segment/network dependency.
 */
internal class LoggingTracker : Tracker {
    override fun track(name: String, properties: JsonObject, target: String?) {
        sampleLog("track name=$name target=$target properties=$properties")
    }

    override fun screen(name: String, properties: JsonObject) {
        sampleLog("screen name=$name properties=$properties")
    }

    override fun identify(userId: String, traits: JsonObject) {
        sampleLog("identify userId=$userId traits=$traits")
    }
}
