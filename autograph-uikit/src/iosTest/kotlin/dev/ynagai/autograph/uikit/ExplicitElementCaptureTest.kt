package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.asJsonObject
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
     * A failing tracker must not unwind into Swift: a Kotlin exception crossing into a Swift caller
     * with no `@Throws` terminates the app, and an analytics event is never worth that.
     */
    @Test
    fun aThrowingElementTrackerDoesNotEscapeIntoTheCaller() {
        val capture = AutographElementCapture(ThrowingElementTracker(), ScopeStack())

        capture.clicked(name = "save_tapped")
        capture.clickedJson(name = "save_tapped", propertiesJson = """{"a":1}""")
    }
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
