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
        var throughMetadataOverload: Bool?
        var stampsInPipeline: Bool { false }

        init(_ delivered: XCTestExpectation) { self.delivered = delivered }

        func record(_ properties: [String: Kotlinx_serialization_jsonJsonElement], throughMetadataOverload: Bool = false) {
            self.properties = properties.mapValues { String(describing: $0) }
            self.throughMetadataOverload = throughMetadataOverload
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
            record(properties, throughMetadataOverload: true)
        }

        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], envelope: Envelope?, metadata: EventMetadata) {}
    }

    /// A Swift `EventValidator` that accepts everything and keeps what it was shown.
    final class RecordingValidator: EventValidator {
        var properties: [String: String]?

        func validate(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement]) -> String? {
            self.properties = properties.mapValues { String(describing: $0) }
            return nil
        }
    }

    private func track(through tracker: Tracker, on stack: ScopeStack = ScopeStack()) {
        var handle = AutographTracking()
        handle.capture = AutographElementCapture(tracker: tracker, scopeStack: stack)
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

    /// The event must carry metadata to take the `MetadataAwareTransport` path: a screen on the stack
    /// gives it a screen view id. Without one it would go through `Transport.track` like the row above.
    func testASwiftMetadataAwareTransportReceivesNestedValuesAsJsonText() {
        let transport = MetadataAwareRecordingTransport(expectation(description: "delivered"))
        let tracker = AutographKt.Autograph { config in
            config.transport(transport: transport)
            config.store = InMemorySeqStore()
        }
        let stack = ScopeStack()
        AutographScreenCapture(tracker: tracker, scopeStack: stack).appeared(name: "Detail")

        track(through: tracker, on: stack)

        wait(for: [transport.delivered], timeout: 5)
        XCTAssertEqual(transport.throughMetadataOverload, true, "the event must reach the metadata overload")
        assertNestedValuesArrivedAsJson(transport.properties)
    }

    func testASwiftValidatorSeesNestedValuesAsJsonTextAndTheEventIsDelivered() {
        let transport = RecordingTransport(expectation(description: "delivered"))
        let validator = RecordingValidator()

        track(through: AutographKt.Autograph { config in
            config.transport(transport: transport)
            config.validator = validator
            config.store = InMemorySeqStore()
        })

        wait(for: [transport.delivered], timeout: 5)
        assertNestedValuesArrivedAsJson(validator.properties)
        assertNestedValuesArrivedAsJson(transport.properties)
    }

    /// A Swift tracker that also adopts `AwaitableCloseTracker`, by forwarding to an `Autograph { }`
    /// tracker — the configuration ADR 0001 measured as supported.
    final class AwaitableSwiftTracker: Tracker, AwaitableCloseTracker {
        let real: Tracker

        init(_ real: Tracker) { self.real = real }

        func track(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement], target: String?) {}
        func screen(name: String, properties: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func identify(userId: String, traits: [String: Kotlinx_serialization_jsonJsonElement]) {}
        func close() {}
        func flush() {}
        func reset() {}
        func notifyForeground() {}
        func notifyBackground() {}

        func closeAndAwait(timeoutMillis: Int64) async throws -> CloseResult {
            try await (real as! AwaitableCloseTracker).closeAndAwait(timeoutMillis: timeoutMillis)
        }
    }

    /// The stand-in `AutographProvider` puts in `LocalTracker` for a Swift tracker must not hide
    /// `AwaitableCloseTracker`: `closeAndAwait` probes it with `is`, so losing it turns a working close
    /// into `Unsupported`.
    func testTheStandInForASwiftTrackerKeepsAwaitableClose() async throws {
        let swift = AwaitableSwiftTracker(AutographKt.Autograph { config in
            config.transport(transport: RecordingTransport(XCTestExpectation(description: "unused")))
            config.store = InMemorySeqStore()
        })

        let standIn = ForeignReceiverKt.callableFromKotlin(swift)
        let result = try await AwaitableCloseKt.closeAndAwait(standIn, timeoutMillis: 1_000)

        XCTAssertFalse(standIn === swift, "a Swift tracker must be replaced by the Kotlin stand-in")
        XCTAssertTrue(standIn is AwaitableCloseTracker)
        XCTAssertFalse(result.isUnsupported, "closeAndAwait must reach the Swift tracker's implementation")
    }

    /// A Swift tracker without the capability gets a stand-in without it.
    func testTheStandInForAPlainSwiftTrackerDoesNotClaimAwaitableClose() {
        let swift = RecordingTracker()

        let standIn = ForeignReceiverKt.callableFromKotlin(swift)

        XCTAssertFalse(standIn === swift)
        XCTAssertFalse(standIn is AwaitableCloseTracker)
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
