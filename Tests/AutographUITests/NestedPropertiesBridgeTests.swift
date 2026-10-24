import Autograph
@testable import AutographUI
import XCTest

/// A Swift `Tracker` or `Transport` given an event with a nested object or array value (#299).
///
/// Before the fix every row here crashed the test process when the call entered Swift — whether or not
/// the conformer read `properties` — with `Could not cast value of type 'AutographKMapAsNSDictionary'`
/// (or `'AutographKListAsNSArray'`) `to '…JsonElement'`. Kotlin/Native hands a nested value across as an
/// `NSDictionary`/`NSArray`, and Swift casts every value of the `[String: JsonElement]` dictionary on
/// entry. Autograph now replaces a nested value with its JSON text when the receiver is written in Swift,
/// so each row asserts the value arrives and decodes back to what was sent.
///
/// The nested values come from Swift through the JSON overload of `AutographTracking.track`, the one way
/// a Swift caller can produce them: Swift cannot construct a `JsonElement` (see `AutographElementCapture`).
final class NestedPropertiesBridgeTests: XCTestCase {

    private static let nestedJson = #"{"o":{"b":1},"l":[1],"n":null,"s":"x"}"#

    /// Records each event's properties as the text `description` gives for each value, and never reads
    /// them otherwise, so a trap can only come from the call itself.
    final class RecordingTracker: Tracker {
        var properties: [[String: String]] = []

        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], target: String?) {
            self.properties.append(properties.mapValues { String(describing: $0) })
        }

        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func identify(userId: String, traits: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func close() {}
        func flush() {}
        func reset() {}
        func notifyForeground() {}
        func notifyBackground() {}
    }

    /// A Swift `Transport`; `MetadataAware` adds the pipeline capability, so the core delivers through
    /// `track(name:properties:envelope:metadata:)` instead.
    class RecordingTransport: NSObject, Transport {
        let delivered: XCTestExpectation
        var properties: [String: String]?
        var stampsInPipeline: Bool { false }

        init(_ delivered: XCTestExpectation) { self.delivered = delivered }

        func record(_ properties: [String: Kotlinx_serialization_jsonJsonElement]) {
            self.properties = properties.mapValues { String(describing: $0) }
            delivered.fulfill()
        }

        func connect(envelopes: EnvelopeSource) {}
        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?) { record(properties) }
        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?) {}
        func identify(userId: String, traits: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?) {}
        func flush() {}
        func reset() {}
    }

    final class MetadataAwareRecordingTransport: RecordingTransport, MetadataAwareTransport {
        override var stampsInPipeline: Bool { true }

        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?, metadata: EventMetadata) {
            record(properties)
        }

        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?, metadata: EventMetadata) {}
    }

    private func track(through tracker: Tracker) {
        var handle = AutographTracking()
        handle.capture = AutographElementCapture(tracker: tracker, scopeStack: ScopeStack())
        handle.track("nested", propertiesJson: Self.nestedJson)
    }

    /// The value's JSON text decoded: `description` may or may not quote a string primitive, so a decoded
    /// `String` is decoded once more.
    private func decoded(_ text: String?, file: StaticString = #filePath, line: UInt = #line) -> Any? {
        guard let data = text?.data(using: .utf8),
              let value = try? JSONSerialization.jsonObject(with: data, options: .fragmentsAllowed) else {
            XCTFail("not JSON: \(String(describing: text))", file: file, line: line)
            return nil
        }
        return (value as? String).map { decoded($0, file: file, line: line) } ?? value
    }

    private func assertNestedValuesArrivedAsJson(_ properties: [String: String]?, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(decoded(properties?["o"], file: file, line: line) as? [String: Int], ["b": 1], file: file, line: line)
        XCTAssertEqual(decoded(properties?["l"], file: file, line: line) as? [Int], [1], file: file, line: line)
        XCTAssertNotNil(properties?["n"], "a null value must still arrive", file: file, line: line)
        XCTAssertEqual(properties?["s"]?.trimmingCharacters(in: ["\""]), "x", file: file, line: line)
    }

    func testASwiftTrackerReceivesNestedValuesAsJsonText() {
        let tracker = RecordingTracker()

        track(through: tracker)

        XCTAssertEqual(tracker.properties.count, 1)
        assertNestedValuesArrivedAsJson(tracker.properties.first)
    }

    func testASwiftTransportReceivesNestedValuesAsJsonText() {
        let transport = RecordingTransport(expectation(description: "delivered"))

        track(through: AutographKt.Autograph { config in
            config.transport(transport: transport)
            config.store = InMemorySeqStore()
        })

        wait(for: [transport.delivered], timeout: 5)
        assertNestedValuesArrivedAsJson(transport.properties)
    }

    func testASwiftMetadataAwareTransportReceivesNestedValuesAsJsonText() {
        let transport = MetadataAwareRecordingTransport(expectation(description: "delivered"))

        track(through: AutographKt.Autograph { config in
            config.transport(transport: transport)
            config.store = InMemorySeqStore()
        })

        wait(for: [transport.delivered], timeout: 5)
        assertNestedValuesArrivedAsJson(transport.properties)
    }

    /// `DebugTransport` is Kotlin, so the core hands it real objects; it must flatten them itself before
    /// calling a Swift delegate.
    func testASwiftTransportUnderDebugTransportReceivesNestedValuesAsJsonText() {
        let transport = RecordingTransport(expectation(description: "delivered"))

        track(through: AutographKt.Autograph { config in
            config.transport(transport: DebugTransport(delegate: transport, log: { _ in }))
            config.store = InMemorySeqStore()
        })

        wait(for: [transport.delivered], timeout: 5)
        assertNestedValuesArrivedAsJson(transport.properties)
    }
}
