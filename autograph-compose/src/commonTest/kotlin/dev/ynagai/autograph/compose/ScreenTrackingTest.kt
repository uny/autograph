package dev.ynagai.autograph.compose

import dev.ynagai.autograph.EmptyJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScreenTrackingTest {

    @Test
    fun attachesPreviousScreenWhenKnown() {
        val result = withPreviousScreen(EmptyJsonObject, "Home")
        assertEquals("Home", (result["previous_screen"] as JsonPrimitive).content)
    }

    @Test
    fun omitsPreviousScreenWhenUnknown() {
        val result = withPreviousScreen(JsonObject(mapOf("k" to JsonPrimitive("v"))), null)
        assertFalse(result.containsKey("previous_screen"))
        assertEquals("v", (result["k"] as JsonPrimitive).content)
    }

    @Test
    fun doesNotOverrideAnExplicitPreviousScreen() {
        val properties = JsonObject(mapOf("previous_screen" to JsonPrimitive("Explicit")))
        val result = withPreviousScreen(properties, "Ambient")
        assertEquals("Explicit", (result["previous_screen"] as JsonPrimitive).content)
    }
}
