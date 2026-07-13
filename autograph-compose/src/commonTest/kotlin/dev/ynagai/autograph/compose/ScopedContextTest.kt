package dev.ynagai.autograph.compose

import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ScopeRecordingTracker : Tracker {
    val tracks = mutableListOf<Triple<String, JsonObject, String?>>()
    val screens = mutableListOf<Pair<String, JsonObject>>()
    val identifies = mutableListOf<Pair<String, JsonObject>>()
    var flushed = 0
    var reset = 0
    var closed = 0

    override fun track(name: String, properties: JsonObject, target: String?) {
        tracks += Triple(name, properties, target)
    }

    override fun screen(name: String, properties: JsonObject) {
        screens += name to properties
    }

    override fun identify(userId: String, traits: JsonObject) {
        identifies += userId to traits
    }

    override fun flush() {
        flushed++
    }

    override fun reset() {
        reset++
    }

    override fun close() {
        closed++
    }
}

private fun scope(vararg pairs: Pair<String, String>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

class ScopedContextTest {

    @Test
    fun mergeFillsScopeKeysAndKeepsCallSiteWinningOnClash() {
        val merged = mergeScope(
            scope("article_id" to "42", "section" to "body"),
            JsonObject(mapOf("section" to JsonPrimitive("explicit"))),
        )
        assertEquals("42", merged["article_id"]?.jsonPrimitive?.content)
        // The call site's explicit value wins over the ambient scope.
        assertEquals("explicit", merged["section"]?.jsonPrimitive?.content)
    }

    @Test
    fun mergeWithEmptyScopeReturnsPropertiesUnchanged() {
        val props = JsonObject(mapOf("k" to JsonPrimitive("v")))
        assertEquals(props, mergeScope(EmptyJsonObject, props))
    }

    @Test
    fun trackAndScreenCarryTheScope() {
        val inner = ScopeRecordingTracker()
        val scoped = ScopedTracker(inner, scope("article_id" to "42"))

        scoped.track("Recipe Saved", EmptyJsonObject, target = "share_button")
        scoped.screen("ArticleDetail")

        assertEquals("42", inner.tracks.single().second["article_id"]?.jsonPrimitive?.content)
        assertEquals("share_button", inner.tracks.single().third)
        assertEquals("42", inner.screens.single().second["article_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun identifyIsNotScoped() {
        val inner = ScopeRecordingTracker()
        val scoped = ScopedTracker(inner, scope("article_id" to "42"))

        scoped.identify("user-1", JsonObject(mapOf("plan" to JsonPrimitive("pro"))))

        val traits = inner.identifies.single().second
        assertNull(traits["article_id"], "identify traits describe the user, not the screen")
        assertEquals("pro", traits["plan"]?.jsonPrimitive?.content)
    }

    @Test
    fun lifecycleCallsDelegateExceptCloseWhichIsANoOp() {
        val inner = ScopeRecordingTracker()
        val scoped = ScopedTracker(inner, scope("article_id" to "42"))

        scoped.flush()
        scoped.reset()
        scoped.close()

        assertEquals(1, inner.flushed)
        assertEquals(1, inner.reset)
        // A scoped view owns no resources; closing it must not tear down the real tracker.
        assertEquals(0, inner.closed)
    }

    @Test
    fun nestedScopesStackWithInnerWinningOnClash() {
        val inner = ScopeRecordingTracker()
        // Simulate what AutographScope's flattening produces for
        // AutographScope("a" to "1", "b" to "1") { AutographScope("b" to "2", "c" to "2") { ... } }.
        val outer = ScopedTracker(inner, scope("a" to "1", "b" to "1"))
        val nested = ScopedTracker(outer.delegate, JsonObject(outer.scope + scope("b" to "2", "c" to "2")))

        nested.track("E", EmptyJsonObject, target = null)

        val props = inner.tracks.single().second
        assertEquals("1", props["a"]?.jsonPrimitive?.content)
        assertEquals("2", props["b"]?.jsonPrimitive?.content, "inner scope overrides the outer one")
        assertEquals("2", props["c"]?.jsonPrimitive?.content)
        // Flattening keeps the decorator a single hop over the original tracker.
        assertTrue(nested.delegate is ScopeRecordingTracker)
    }
}
