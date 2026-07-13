package dev.ynagai.autograph.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackingPlanParserTest {

    @Test
    fun parsesAnEventWithRequiredAndOptionalProperties() {
        val json = """
            {
              "events": [
                {
                  "name": "Recipe Saved",
                  "properties": {
                    "type": "object",
                    "properties": {
                      "target": { "type": "string" },
                      "quantity": { "type": "integer" }
                    },
                    "required": ["target"]
                  }
                }
              ]
            }
        """.trimIndent()

        val events = parseTrackingPlan(json)

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("Recipe Saved", event.name)
        assertEquals(
            setOf(
                PropertySchema("target", PropertyType.STRING, required = true),
                PropertySchema("quantity", PropertyType.INTEGER, required = false),
            ),
            event.properties.toSet(),
        )
    }

    @Test
    fun parsesAnEventWithNoProperties() {
        val json = """{ "events": [ { "name": "App Opened" } ] }"""

        val events = parseTrackingPlan(json)

        assertEquals(EventSchema("App Opened", emptyList()), events.single())
    }

    @Test
    fun parsesAllSupportedPropertyTypes() {
        val json = """
            {
              "events": [
                {
                  "name": "E",
                  "properties": {
                    "type": "object",
                    "properties": {
                      "a": { "type": "string" },
                      "b": { "type": "integer" },
                      "c": { "type": "number" },
                      "d": { "type": "boolean" }
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val types = parseTrackingPlan(json).single().properties.associate { it.name to it.type }

        assertEquals(
            mapOf(
                "a" to PropertyType.STRING,
                "b" to PropertyType.INTEGER,
                "c" to PropertyType.NUMBER,
                "d" to PropertyType.BOOLEAN,
            ),
            types,
        )
    }

    @Test
    fun throwsOnMissingEventsArray() {
        assertFailsWith<TrackingPlanParseException> {
            parseTrackingPlan("""{ "notEvents": [] }""")
        }
    }

    @Test
    fun throwsOnInvalidJson() {
        assertFailsWith<TrackingPlanParseException> {
            parseTrackingPlan("not json")
        }
    }

    @Test
    fun throwsOnEventMissingName() {
        val exception = assertFailsWith<TrackingPlanParseException> {
            parseTrackingPlan("""{ "events": [ { "properties": {} } ] }""")
        }
        assertTrue(exception.message!!.contains("name"))
    }

    @Test
    fun throwsOnBlankEventName() {
        assertFailsWith<TrackingPlanParseException> {
            parseTrackingPlan("""{ "events": [ { "name": "   " } ] }""")
        }
    }

    @Test
    fun throwsOnUnsupportedPropertyType() {
        val json = """
            {
              "events": [
                { "name": "E", "properties": { "type": "object", "properties": { "a": { "type": "array" } } } }
              ]
            }
        """.trimIndent()

        val exception = assertFailsWith<TrackingPlanParseException> { parseTrackingPlan(json) }
        assertTrue(exception.message!!.contains("array"))
    }

    @Test
    fun throwsOnPropertyMissingType() {
        val json = """
            {
              "events": [
                { "name": "E", "properties": { "type": "object", "properties": { "a": {} } } }
              ]
            }
        """.trimIndent()

        assertFailsWith<TrackingPlanParseException> { parseTrackingPlan(json) }
    }

    @Test
    fun throwsOnDuplicateEventNames() {
        val json = """
            {
              "events": [
                { "name": "Recipe Saved" },
                { "name": "Recipe Saved" }
              ]
            }
        """.trimIndent()

        assertFailsWith<TrackingPlanParseException> { parseTrackingPlan(json) }
    }

    @Test
    fun throwsWhenTwoEventNamesGenerateTheSameFunctionName() {
        val json = """
            {
              "events": [
                { "name": "Recipe Saved" },
                { "name": "recipe-saved" }
              ]
            }
        """.trimIndent()

        assertFailsWith<TrackingPlanParseException> { parseTrackingPlan(json) }
    }

    @Test
    fun throwsWhenTwoPropertyNamesGenerateTheSameParameterName() {
        val json = """
            {
              "events": [
                {
                  "name": "E",
                  "properties": {
                    "type": "object",
                    "properties": { "target_id": { "type": "string" }, "targetId": { "type": "string" } }
                  }
                }
              ]
            }
        """.trimIndent()

        assertFailsWith<TrackingPlanParseException> { parseTrackingPlan(json) }
    }
}
