package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.Autograph
import dev.ynagai.autograph.Envelope
import dev.ynagai.autograph.EventKinds
import dev.ynagai.autograph.InMemorySeqStore
import dev.ynagai.autograph.RESERVED_METADATA_KEY
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.Transport
import dev.ynagai.autograph.asJsonObject
import dev.ynagai.autograph.context.ScopeStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Covers [AutographElementCapture] — the Swift-facing explicit click entry point.
 *
 * The load-bearing case here is [aSiblingScopeSurvivesBecauseItIsPassedInNotResolved]: it is the
 * reason this class takes `scope` as a parameter instead of reading `ScopeStack`, and it is the one
 * behaviour a future "simplification" would most plausibly undo (reading the stack looks tidier and
 * would pass every other test in this file).
 */
class ExplicitElementCaptureTest {

    @Test
    fun theCallSiteWinsOverTheScopeOnAClash() {
        val tracker = RecordingElementTracker()
        val capture = AutographElementCapture(tracker, ScopeStack())

        capture.clicked(
            name = "save_tapped",
            properties = mapOf("plan" to "pro", "shared" to "call_site"),
            scope = mapOf("article_id" to "42", "shared" to "scope"),
        )

        val properties = tracker.properties.single()
        // The scope fills a key the call site did not set...
        assertEquals("42", properties["article_id"]?.jsonPrimitive?.content)
        // ...and loses the one it did, matching `mergeScope` on the Compose side.
        assertEquals("call_site", properties["shared"]?.jsonPrimitive?.content)
        assertEquals("pro", properties["plan"]?.jsonPrimitive?.content)
    }

    @Test
    fun aScopeEntryUnderTheReservedMetadataKeyIsLeftOut() {
        val tracker = RecordingElementTracker()
        val capture = AutographElementCapture(tracker, ScopeStack())

        capture.clicked(
            name = "save_tapped",
            scope = mapOf(RESERVED_METADATA_KEY to "click", "article_id" to "42"),
        )

        val properties = tracker.properties.single()
        assertFalse(RESERVED_METADATA_KEY in properties, properties.toString())
        assertEquals("42", properties["article_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun screenAndSectionComeFromTheStackAndOverwriteTheirReservedKeys() {
        val tracker = RecordingElementTracker()
        val stack = ScopeStack()
        stack.push(screen = "ArticleDetail", section = "header")
        val capture = AutographElementCapture(tracker, stack)

        capture.clicked(
            name = "save_tapped",
            // A call site that sets the reserved keys itself must not win: they are library-managed.
            properties = mapOf("screen" to "wrong", "section" to "wrong"),
        )

        val properties = tracker.properties.single()
        assertEquals("ArticleDetail", properties["screen"]?.jsonPrimitive?.content)
        assertEquals("header", properties["section"]?.jsonPrimitive?.content)
    }

    /**
     * The reason `scope` is a parameter. Two sibling frames are on the stack — the shape a list whose
     * rows each own a scope produces — so `ScopeStack.resolveScope()` drops both as ambiguous. An
     * explicit call site knows exactly which row it is, so its scope must still attribute.
     *
     * The first assertion is what would break if this class ever read the stack's scope instead; the
     * second proves the premise is real rather than assumed, so the test cannot pass vacuously by the
     * stack happening to resolve the scope after all.
     */
    @Test
    fun aSiblingScopeSurvivesBecauseItIsPassedInNotResolved() {
        val tracker = RecordingElementTracker()
        val stack = ScopeStack()
        // Two roots, neither the other's ancestor: ambiguous by construction.
        stack.push(scope = JsonObject(mapOf("row" to JsonPrimitive("first"))))
        stack.push(scope = JsonObject(mapOf("row" to JsonPrimitive("second"))))

        // The premise: the stack itself refuses to choose between them.
        assertNull(stack.current().scope["row"], "expected ScopeStack to drop ambiguous sibling scopes")

        AutographElementCapture(tracker, stack).clicked(
            name = "row_tapped",
            scope = mapOf("row" to "second"),
        )

        assertEquals("second", tracker.properties.single()["row"]?.jsonPrimitive?.content)
    }

    @Test
    fun targetIsForwardedAsTheReservedField() {
        val tracker = RecordingElementTracker()
        AutographElementCapture(tracker, ScopeStack())
            .clicked(name = "save_tapped", target = "save_button")

        assertEquals("save_button", tracker.targets.single())
    }

    @Test
    fun jsonPropertiesCarryNonStringValues() {
        val tracker = RecordingElementTracker()
        AutographElementCapture(tracker, ScopeStack())
            .clickedJson(name = "save_tapped", propertiesJson = """{"count":3,"ok":true}""")

        val properties = tracker.properties.single()
        assertEquals("3", properties["count"]?.jsonPrimitive?.content)
        assertEquals("true", properties["ok"]?.jsonPrimitive?.content)
    }

    /**
     * Malformed or non-object JSON loses the properties but **keeps the event**. Asserting the event
     * arrived is the point: an assertion that merely checked "properties are empty" would also pass if
     * the event were dropped entirely, which is the failure this behaviour exists to avoid.
     */
    @Test
    fun unusableJsonDropsThePropertiesNotTheEvent() {
        for (json in listOf("not json", "[1,2]", "\"a string\"", "")) {
            val tracker = RecordingElementTracker()
            AutographElementCapture(tracker, ScopeStack())
                .clickedJson(name = "save_tapped", propertiesJson = json)

            assertEquals(listOf("save_tapped"), tracker.names, "event dropped for input: $json")
            assertTrue(tracker.properties.single().isEmpty(), "expected empty properties for: $json")
        }
    }

    /**
     * `buttonClicked` is `AutographButton`'s path and the only one that claims `kind` `click`: it is
     * written over a scope entry under the reserved key rather than losing to it.
     */
    @Test
    fun onlyButtonClickedMarksTheEventAsAClick() {
        val tracker = RecordingElementTracker()
        val capture = AutographElementCapture(tracker, ScopeStack())

        capture.buttonClicked(name = "save_tapped", scope = mapOf(RESERVED_METADATA_KEY to "impression"))
        capture.clicked(name = "save_tapped")
        capture.clickedJson(name = "save_tapped", propertiesJson = """{"a":1}""")

        val (button, plain, json) = tracker.properties
        assertEquals(JsonPrimitive("""{"kind":"${EventKinds.CLICK}"}"""), button[RESERVED_METADATA_KEY])
        assertFalse(RESERVED_METADATA_KEY in plain, plain.toString())
        assertFalse(RESERVED_METADATA_KEY in json, json.toString())
    }

    /**
     * Through the tracker [Autograph] builds: the mark leaves `properties` and lands on the envelope,
     * which a recording fake cannot show, since a fake does not strip the reserved key.
     */
    @Test
    fun buttonClickedReachesTheEnvelopeAsKindClick() {
        val transport = EnvelopeRecordingTransport()
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
        }
        val capture = AutographElementCapture(tracker, ScopeStack())

        capture.buttonClicked(name = "save_tapped", properties = mapOf("plan" to "pro"))
        capture.clicked(name = "delete_tapped")

        val (button, plain) = transport.tracked
        assertEquals(EventKinds.CLICK, button.second?.metadata?.kind)
        assertFalse(RESERVED_METADATA_KEY in button.first, button.first.toString())
        assertEquals("pro", button.first["plan"]?.jsonPrimitive?.content)
        assertNull(plain.second?.metadata)
    }

    /**
     * #242: a click on a SwiftUI screen carries the id of the screen's visit, on the envelope, from
     * every entry point — `clickedJson` included, which claims no kind — and a click once the screen
     * has gone carries none.
     */
    @Test
    fun aClickCarriesTheVisitOfTheScreenItHappenedOn() {
        val transport = EnvelopeRecordingTransport()
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
        }
        val stack = ScopeStack()
        val capture = AutographElementCapture(tracker, stack)
        val view = AutographScreenCapture(tracker, stack).appeared("Detail")

        capture.buttonClicked(name = "save_tapped")
        capture.clickedJson(name = "share_tapped", propertiesJson = "{}")
        view.disappeared()
        capture.clicked(name = "late_tapped")

        val visit = transport.screens.single()?.metadata?.screenViewId
        assertTrue(!visit.isNullOrEmpty(), "the screen view carries an id")
        val (button, json, late) = transport.tracked.map { it.second?.metadata }
        assertEquals(visit, button?.screenViewId)
        assertEquals(EventKinds.CLICK, button?.kind)
        assertEquals(visit, json?.screenViewId)
        assertNull(json?.kind)
        assertNull(late?.screenViewId)
    }

    /**
     * Two SwiftUI screens on display side by side (an iPad split view): their frames are roots, so a
     * click cannot be tied to either visit and carries none, though the name still resolves.
     */
    @Test
    fun aClickBesideTwoVisibleScreensCarriesNoVisit() {
        val tracker = RecordingElementTracker()
        val stack = ScopeStack()
        val screens = AutographScreenCapture(tracker, stack)
        screens.appeared("Sidebar")
        screens.appeared("Detail")

        AutographElementCapture(tracker, stack).clickedJson(name = "save_tapped", propertiesJson = "{}")

        val properties = tracker.properties.single()
        assertEquals("Detail", properties["screen"]?.jsonPrimitive?.content)
        assertFalse(RESERVED_METADATA_KEY in properties, properties.toString())
    }

    /**
     * A failing tracker must not unwind into Swift: a Kotlin exception crossing into a Swift caller
     * with no `@Throws` terminates the app, and an analytics event is never worth that.
     */
    @Test
    fun aThrowingElementTrackerDoesNotEscapeIntoTheCaller() {
        val capture = AutographElementCapture(ThrowingElementTracker(), ScopeStack())

        capture.clicked(name = "save_tapped")
        capture.clickedJson(name = "save_tapped", propertiesJson = """{"a":1}""")
        capture.buttonClicked(name = "save_tapped")
    }
}

private class EnvelopeRecordingTransport : Transport {
    val tracked = mutableListOf<Pair<JsonObject, Envelope?>>()
    val screens = mutableListOf<Envelope?>()

    override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        tracked += properties.asJsonObject() to envelope
    }

    override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
        screens += envelope
    }

    override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) = Unit
}

private class RecordingElementTracker : Tracker {
    val targets = mutableListOf<String?>()
    val names = mutableListOf<String>()
    val properties = mutableListOf<JsonObject>()

    override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {
        targets += target
        names += name
        this.properties += properties.asJsonObject()
    }

    override fun screen(name: String, properties: Map<String, JsonElement>) = Unit

    override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
}

private class ThrowingElementTracker : Tracker {
    override fun track(name: String, properties: Map<String, JsonElement>, target: String?): Unit =
        throw IllegalStateException("transport is down")

    override fun screen(name: String, properties: Map<String, JsonElement>) = Unit

    override fun identify(userId: String, traits: Map<String, JsonElement>) = Unit
}
