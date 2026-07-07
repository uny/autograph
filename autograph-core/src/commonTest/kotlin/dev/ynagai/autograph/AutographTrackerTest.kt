package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingTransport(
    override val stampsInPipeline: Boolean = false,
) : Transport {
    var envelopes: EnvelopeSource? = null
    val calls = mutableListOf<Triple<String, String, Envelope?>>()

    override fun connect(envelopes: EnvelopeSource) {
        this.envelopes = envelopes
    }

    override fun track(name: String, properties: JsonObject, envelope: Envelope?) {
        calls += Triple("track", name, envelope)
    }

    override fun screen(name: String, properties: JsonObject, envelope: Envelope?) {
        calls += Triple("screen", name, envelope)
    }

    override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) {
        calls += Triple("identify", userId, envelope)
    }
}

class AutographTrackerTest {

    private fun tracker(transport: Transport): Tracker = Autograph {
        transport(transport)
        store = InMemorySeqStore()
    }

    @Test
    fun coreStampsWhenTransportDoesNot() {
        val transport = RecordingTransport(stampsInPipeline = false)
        val tracker = tracker(transport)

        tracker.track("Recipe Saved")
        tracker.screen("RecipeDetail")

        assertEquals(2, transport.calls.size)
        val (_, _, first) = transport.calls[0]
        val (_, _, second) = transport.calls[1]
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(1L, first.seq)
        assertEquals(2L, second.seq)
    }

    @Test
    fun coreDoesNotStampWhenTransportStampsInPipeline() {
        val transport = RecordingTransport(stampsInPipeline = true)
        val tracker = tracker(transport)

        tracker.track("Recipe Saved")

        assertNull(transport.calls.single().third)
        assertNotNull(transport.envelopes, "transport must receive the envelope source on connect")
        assertTrue(transport.envelopes!!.stamp().seq == 1L)
    }
}
