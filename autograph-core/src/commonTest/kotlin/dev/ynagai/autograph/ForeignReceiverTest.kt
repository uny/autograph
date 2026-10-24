package dev.ynagai.autograph

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The Kotlin half of #299: a receiver written in Kotlin keeps getting real nested objects and arrays,
 * on every platform — iOS included, which is where the receiver check actually runs. The Swift half
 * (a Swift conformer gets JSON text instead of trapping) can only run in `Tests/AutographUITests`.
 */
@OptIn(AutographInternalApi::class)
class ForeignReceiverTest {

    private val nested: JsonObject = buildJsonObject {
        put("o", buildJsonObject { put("b", 1) })
        put("l", buildJsonArray { add(JsonPrimitive(1)) })
        put("s", "x")
        put("n", JsonNull)
    }

    private class RecordingTransport : Transport {
        val tracked = mutableListOf<Map<String, JsonElement>>()
        val screened = mutableListOf<Map<String, JsonElement>>()
        val identified = mutableListOf<Map<String, JsonElement>>()

        override fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
            tracked += properties
        }

        override fun screen(name: String, properties: Map<String, JsonElement>, envelope: Envelope?) {
            screened += properties
        }

        override fun identify(userId: String, traits: Map<String, JsonElement>, envelope: Envelope?) {
            identified += traits
        }
    }

    @Test
    fun aKotlinReceiverIsNotTreatedAsForeign() {
        val anonymous = object : Tracker {
            override fun track(name: String, properties: Map<String, JsonElement>, target: String?) {}
            override fun screen(name: String, properties: Map<String, JsonElement>) {}
            override fun identify(userId: String, traits: Map<String, JsonElement>) {}
        }

        assertFalse(isImplementedOutsideKotlin(anonymous))
        assertFalse(isImplementedOutsideKotlin(RecordingTransport()))
        assertFalse(isImplementedOutsideKotlin(DebugTransport(RecordingTransport()) {}))
        assertSame(nested, nested.deliverableTo(anonymous))
    }

    @Test
    fun aKotlinTransportReceivesNestedValuesAsObjectsAndArrays() {
        val transport = RecordingTransport()
        val tracker = Autograph {
            transport(DebugTransport(transport) {})
            store = InMemorySeqStore()
            dispatcher = Dispatchers.Unconfined
        }

        tracker.track("t", nested)
        tracker.screen("s", nested)
        tracker.identify("u", nested)

        for (received in listOf(transport.tracked.single(), transport.screened.single(), transport.identified.single())) {
            assertIs<JsonObject>(received["o"])
            assertIs<JsonArray>(received["l"])
        }
    }

    @Test
    fun nestedValuesBecomeTheirJsonTextAndPrimitivesAreLeftAlone() {
        val flattened = nested.withNestedValuesAsJsonText()

        assertEquals(JsonPrimitive("""{"b":1}"""), flattened["o"])
        assertEquals(JsonPrimitive("[1]"), flattened["l"])
        assertEquals(JsonPrimitive("x"), flattened["s"])
        assertSame(JsonNull, flattened["n"])
        assertEquals(nested.keys.toList(), flattened.keys.toList())
    }

    @Test
    fun propertiesWithoutNestedValuesAreReturnedAsIs() {
        val flat = buildJsonObject { put("s", "x") }

        assertSame(flat, flat.withNestedValuesAsJsonText())
    }
}
