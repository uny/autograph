package dev.ynagai.autograph

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * These properties as [receiver] can take them: unchanged for a receiver written in Kotlin, and with
 * every nested object or array value replaced by its JSON text for one written in Swift or
 * Objective-C (#299).
 *
 * A Swift [Tracker] or [Transport] cannot receive a nested value at all. Kotlin/Native hands a
 * [JsonObject] value across as an `NSDictionary` and a [JsonArray] value as an `NSArray`, not as the
 * `JsonElement` the signature promises, and Swift converts the whole `[String: JsonElement]` dictionary
 * as the call enters it, so the process traps before the conformer's body runs — whether it reads the
 * value or not. A JSON string is what `__autograph` (#298) and the Segment bridge already send for the
 * same reason, and a Swift conformer can decode it with `JSONSerialization`. Primitive values, `null`
 * included, cross intact and are left alone.
 *
 * Every call Autograph's own modules make on a [Tracker] or [Transport] passes its properties through
 * this, so a Kotlin receiver keeps getting real objects and arrays. A call that does not go through
 * Autograph — Kotlin code that holds a Swift tracker and calls it directly, or a Kotlin wrapper
 * forwarding to a Swift transport — is not covered and still traps.
 *
 * `@AutographInternalApi`: public only so `autograph-compose`, `autograph-context` and
 * `autograph-uikit` can apply it at their own call sites.
 */
@AutographInternalApi
public fun Map<String, JsonElement>.deliverableTo(receiver: Any): Map<String, JsonElement> =
    if (isImplementedOutsideKotlin(receiver)) withNestedValuesAsJsonText() else this

/** Whether [receiver] is an object written in Swift or Objective-C rather than in Kotlin. */
internal expect fun isImplementedOutsideKotlin(receiver: Any): Boolean

internal fun Map<String, JsonElement>.withNestedValuesAsJsonText(): Map<String, JsonElement> {
    if (values.none { it.isNested() }) return this
    return JsonObject(mapValues { (_, value) -> if (value.isNested()) JsonPrimitive(value.toString()) else value })
}

private fun JsonElement.isNested(): Boolean = this is JsonObject || this is JsonArray
