package dev.ynagai.autograph.segment

import dev.ynagai.autograph.Envelope
import dev.ynagai.autograph.Transport
import kotlinx.serialization.json.JsonObject

/**
 * Bridge to Segment's `analytics-swift` SDK, implemented in Swift by the app.
 *
 * `analytics-swift` exposes a pure-Swift API that is not visible to Kotlin/Native
 * interop, so the app adds the SDK via SwiftPM and injects an adapter implementing
 * this interface. A ready-made adapter is published as the `autograph-segment-swift`
 * Swift package, making the wiring a single line.
 *
 * JSON payloads cross the bridge as strings; the Swift adapter decodes them, applies
 * [messageId] to the event's `messageId`, and merges the instrumentation object into
 * `context.instrumentation` via an enrichment closure.
 */
public interface SegmentBridge {
    public fun track(name: String, propertiesJson: String, messageId: String, instrumentationJson: String)
    public fun screen(name: String, propertiesJson: String, messageId: String, instrumentationJson: String)
    public fun identify(userId: String, traitsJson: String, messageId: String, instrumentationJson: String)
    public fun flush()
    public fun reset()
}

/** Delivers events through Segment's `analytics-swift` SDK via an injected [SegmentBridge]. */
public class SegmentTransport(
    private val bridge: SegmentBridge,
) : Transport {

    // Stamping happens in the core: the Swift side applies the envelope per call via
    // enrichment closures, so unlike Android there is no in-pipeline stamping.
    override val stampsInPipeline: Boolean get() = false

    override fun track(name: String, properties: JsonObject, envelope: Envelope?) {
        bridge.track(name, properties.toString(), envelope?.eventId.orEmpty(), envelope?.toJson()?.toString().orEmpty())
    }

    override fun screen(name: String, properties: JsonObject, envelope: Envelope?) {
        bridge.screen(name, properties.toString(), envelope?.eventId.orEmpty(), envelope?.toJson()?.toString().orEmpty())
    }

    override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) {
        // Forward the stamped envelope like track/screen: the core advanced the sequence
        // counter for this identify (stampsInPipeline=false), so dropping the envelope would
        // leave a phantom gap and strip the event_id. Matches the Android in-pipeline path.
        bridge.identify(userId, traits.toString(), envelope?.eventId.orEmpty(), envelope?.toJson()?.toString().orEmpty())
    }

    override fun flush() {
        bridge.flush()
    }

    override fun reset() {
        bridge.reset()
    }
}
