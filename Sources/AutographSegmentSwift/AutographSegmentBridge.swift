import AutographSegment
import Foundation
import Segment

/// Reference implementation of `SegmentBridge` (see `SegmentTransport.kt`'s `iosMain`), wiring
/// Autograph's Kotlin core to Segment's `analytics-swift` SDK.
///
/// `analytics-swift`'s event model (`RawEvent`/`TrackEvent`) is built on Swift structs and
/// generics, neither of which bridge to Objective-C — so Kotlin/Native can't call into it
/// directly, even via a direct SwiftPM dependency. This adapter is plain Swift: it conforms to
/// the `SegmentBridge` protocol Kotlin exports through `AutographSegment.xcframework`, and
/// internally drives `analytics-swift`'s ordinary (non-ObjC) Swift API.
public final class AutographSegmentBridge: NSObject, SegmentBridge {

    private let analytics: Analytics

    public init(analytics: Analytics) {
        self.analytics = analytics
    }

    public func track(name: String, propertiesJson: String, messageId: String, instrumentationJson: String) {
        analytics.track(
            name: name,
            properties: EnvelopeStamp.decode(propertiesJson),
            enrichments: [EnvelopeStamp.enrichment(messageId: messageId, instrumentationJson: instrumentationJson)]
        )
    }

    public func screen(name: String, propertiesJson: String, messageId: String, instrumentationJson: String) {
        analytics.screen(
            title: name,
            properties: EnvelopeStamp.decode(propertiesJson),
            enrichments: [EnvelopeStamp.enrichment(messageId: messageId, instrumentationJson: instrumentationJson)]
        )
    }

    public func identify(userId: String, traitsJson: String, messageId: String, instrumentationJson: String) {
        analytics.identify(
            userId: userId,
            traits: EnvelopeStamp.decode(traitsJson),
            enrichments: [EnvelopeStamp.enrichment(messageId: messageId, instrumentationJson: instrumentationJson)]
        )
    }

    public func flush() {
        analytics.flush()
    }

    public func reset() {
        analytics.reset()
    }
}
