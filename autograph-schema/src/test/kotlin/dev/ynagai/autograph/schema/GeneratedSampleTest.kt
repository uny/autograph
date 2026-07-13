package dev.ynagai.autograph.schema

import dev.ynagai.autograph.Autograph
import dev.ynagai.autograph.InMemorySeqStore
import dev.ynagai.autograph.test.InMemoryTestTransport
import dev.ynagai.autograph.test.assertEventFired
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves the shape generateTrackerExtensions() produces (see GeneratedSample.kt) actually works. */
class GeneratedSampleTest {

    // Enforces the "kept byte-for-byte in sync" claim in GeneratedSample.kt's header comment —
    // without this, the hand-written file and the real generator could silently drift apart.
    @Test
    fun generatedSampleMatchesTheRealGeneratorOutputForTheSameSchema() {
        val events = listOf(
            EventSchema(
                "Recipe Saved",
                listOf(
                    PropertySchema("quantity", PropertyType.INTEGER, required = false),
                    PropertySchema("target", PropertyType.STRING, required = true),
                ),
            ),
        )
        val marker = "public fun Tracker.trackRecipeSaved("
        // Bounded to just this one function's closing brace — GeneratedSample.kt has more than one
        // function in it (see backtickEscapedKeywordParameterCompilesAndFiresTheRightEvent below).
        fun extractFunction(source: String) = source.substringAfter(marker).let { marker + it.substringBefore("\n}") + "\n}" }

        val generatedFunction = extractFunction(generateTrackerExtensions(events, "p"))
        val sampleFunction = extractFunction(File("src/test/kotlin/dev/ynagai/autograph/schema/GeneratedSample.kt").readText())

        assertEquals(sampleFunction.trim(), generatedFunction.trim())
    }

    @Test
    fun generatedFunctionFiresTheRightEventWithBothPropertiesSet() {
        val transport = InMemoryTestTransport()
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
        }

        tracker.trackRecipeSaved(target = "share_button", quantity = 2L)

        transport.assertEventFired(
            "Recipe Saved",
            properties = mapOf("target" to "share_button", "quantity" to 2L),
            exact = true,
        )
    }

    @Test
    fun backtickEscapedKeywordParameterCompilesAndFiresTheRightEvent() {
        val transport = InMemoryTestTransport()
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
        }

        tracker.trackE(`in` = "value")

        transport.assertEventFired("E", properties = mapOf("in" to "value"), exact = true)
    }

    @Test
    fun generatedFunctionOmitsTheOptionalPropertyWhenLeftNull() {
        val transport = InMemoryTestTransport()
        val tracker = Autograph {
            transport(transport)
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
        }

        tracker.trackRecipeSaved(target = "share_button")

        transport.assertEventFired(
            "Recipe Saved",
            properties = mapOf("target" to "share_button"),
            exact = true,
        )
    }
}
