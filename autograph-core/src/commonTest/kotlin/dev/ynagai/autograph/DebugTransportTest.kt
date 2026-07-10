package dev.ynagai.autograph

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeTransport(override val stampsInPipeline: Boolean = false) : Transport {
    var connected: EnvelopeSource? = null
    val calls = mutableListOf<String>()
    var flushed = false
    var wasReset = false

    override fun connect(envelopes: EnvelopeSource) {
        connected = envelopes
    }

    override fun track(name: String, properties: JsonObject, envelope: Envelope?) {
        calls += "track:$name"
    }

    override fun screen(name: String, properties: JsonObject, envelope: Envelope?) {
        calls += "screen:$name"
    }

    override fun identify(userId: String, traits: JsonObject, envelope: Envelope?) {
        calls += "identify:$userId"
    }

    override fun flush() {
        flushed = true
    }

    override fun reset() {
        wasReset = true
    }
}

private val testEnvelope = Envelope(
    eventId = "evt-123",
    session = SessionInfo(id = "sess-1", startEpochMillis = 1000L),
    seq = 1L,
    globalSeq = null,
    sdk = "autograph/test",
    eventTimestamp = "2024-01-01T00:00:00Z",
)

class DebugTransportTest {

    @Test
    fun delegatesEveryCallToTheWrappedTransport() {
        val delegate = FakeTransport()
        val transport = DebugTransport(delegate) {}

        val envelopes = object : EnvelopeSource {
            override fun stamp() = testEnvelope
            override fun reset() {}
        }
        transport.connect(envelopes)
        transport.track("Recipe Saved", JsonObject(emptyMap()), testEnvelope)
        transport.screen("Home", JsonObject(emptyMap()), testEnvelope)
        transport.identify("user-1", JsonObject(emptyMap()), testEnvelope)
        transport.flush()
        transport.reset()

        assertEquals(envelopes, delegate.connected, "connect() must be forwarded")
        assertEquals(listOf("track:Recipe Saved", "screen:Home", "identify:user-1"), delegate.calls)
        assertTrue(delegate.flushed)
        assertTrue(delegate.wasReset)
    }

    @Test
    fun reflectsTheDelegatesStampsInPipeline() {
        assertEquals(false, DebugTransport(FakeTransport(stampsInPipeline = false)) {}.stampsInPipeline)
        assertEquals(true, DebugTransport(FakeTransport(stampsInPipeline = true)) {}.stampsInPipeline)
    }

    @Test
    fun logsEveryEventBeforeDelivering() {
        val logged = mutableListOf<String>()
        val transport = DebugTransport(FakeTransport(), log = { logged += it })

        val properties = buildJsonObject { put("key", "value") }
        transport.track("Recipe Saved", properties, testEnvelope)

        val line = logged.single()
        assertTrue(line.contains("track"), line)
        assertTrue(line.contains("Recipe Saved"), line)
        assertTrue(line.contains("key"), line)
        assertTrue(line.contains("evt-123"), "the logged line must include the envelope: $line")
    }

    @Test
    fun logsNullEnvelopeWhenDelegateStampsInItsOwnPipeline() {
        val logged = mutableListOf<String>()
        val transport = DebugTransport(FakeTransport(stampsInPipeline = true), log = { logged += it })

        transport.track("Recipe Saved", JsonObject(emptyMap()), envelope = null)

        assertTrue(logged.single().contains("envelope=null"), logged.single())
    }
}
