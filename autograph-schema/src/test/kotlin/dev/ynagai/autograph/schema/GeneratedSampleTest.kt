package dev.ynagai.autograph.schema

import dev.ynagai.autograph.Autograph
import dev.ynagai.autograph.InMemorySeqStore
import dev.ynagai.autograph.test.InMemoryTestTransport
import dev.ynagai.autograph.test.assertEventFired
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/** Proves the shape generateTrackerExtensions() produces (see GeneratedSample.kt) actually works. */
class GeneratedSampleTest {

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
