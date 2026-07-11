import Segment
import XCTest

@testable import AutographSegmentSwift

final class EnvelopeStampTests: XCTestCase {

    func testDecodeParsesAFlatJsonObject() {
        let decoded = EnvelopeStamp.decode(#"{"target":"share_button","count":3}"#)

        XCTAssertEqual(decoded?["target"] as? String, "share_button")
        XCTAssertEqual(decoded?["count"] as? Int, 3)
    }

    func testDecodeReturnsNilForEmptyString() {
        XCTAssertNil(EnvelopeStamp.decode(""))
    }

    func testEnrichmentStampsMessageIdAndMergesInstrumentation() {
        var event = TrackEvent(event: "Recipe Saved", properties: nil)
        event.messageId = "segment-original-message-id"
        event.context = try? JSON(["existing": "value"])

        let enrichment = EnvelopeStamp.enrichment(
            messageId: "evt-123",
            instrumentationJson: #"{"event_id":"evt-123","seq":7}"#
        )

        let result = enrichment(event) as? TrackEvent

        XCTAssertEqual(result?.messageId, "evt-123")
        let context = result?.context?.dictionaryValue
        XCTAssertEqual(context?["existing"] as? String, "value", "must not clobber existing context entries")
        let instrumentation = context?["instrumentation"] as? [String: Any]
        XCTAssertEqual(instrumentation?["event_id"] as? String, "evt-123")
        XCTAssertEqual(instrumentation?["seq"] as? Int, 7)
    }

    func testEnrichmentLeavesContextUntouchedWhenThereIsNoExistingContext() {
        var event = TrackEvent(event: "Recipe Saved", properties: nil)
        event.context = nil

        let enrichment = EnvelopeStamp.enrichment(
            messageId: "evt-456",
            instrumentationJson: #"{"event_id":"evt-456","seq":1}"#
        )

        let result = enrichment(event) as? TrackEvent

        XCTAssertEqual(result?.messageId, "evt-456")
        XCTAssertEqual(result?.context?.dictionaryValue?["instrumentation"] as? [String: Any] != nil, true)
    }
}
