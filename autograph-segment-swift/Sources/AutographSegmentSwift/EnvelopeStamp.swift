import Foundation
import Segment

/// Decodes the JSON strings Kotlin passes across the bridge and builds the `EnrichmentClosure`
/// that stamps `messageId` and merges the instrumentation envelope into `context.instrumentation`
/// — mirroring `AutographPlugin`'s behavior on Android (`SegmentTransport.kt`'s `androidMain`).
///
/// Pulled out of `AutographSegmentBridge` so this stamping logic is testable without spinning up
/// a full `Analytics` pipeline.
enum EnvelopeStamp {

    static func decode(_ json: String) -> [String: Any]? {
        guard let data = json.data(using: .utf8), !json.isEmpty else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    static func enrichment(messageId: String, instrumentationJson: String) -> EnrichmentClosure {
        { event in
            guard var workingEvent = event else { return event }
            workingEvent.messageId = messageId
            if let instrumentation = decode(instrumentationJson) {
                var context = workingEvent.context?.dictionaryValue ?? [:]
                context["instrumentation"] = instrumentation
                workingEvent.context = try? JSON(context)
            }
            return workingEvent
        }
    }
}
