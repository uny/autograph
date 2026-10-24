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
 * included, cross intact and are left alone. The replacement loses the type: a Swift receiver cannot
 * tell an encoded object from a string value that happens to hold the same JSON text, so it should
 * decode a value only where it knows that key carries a nested value.
 *
 * Every call Autograph's own modules make on a [Tracker], [Transport] or [EventValidator] passes its
 * properties through this, so a Kotlin receiver keeps getting real objects and arrays, and
 * `LocalTracker` is covered through [callableFromKotlin]. A call that does not go through Autograph —
 * Kotlin code that holds a Swift tracker and calls it directly, or a Kotlin wrapper forwarding to a
 * Swift transport — is not covered and still traps.
 *
 * `@AutographInternalApi`: public only so `autograph-compose`, `autograph-context` and
 * `autograph-uikit` can apply it at their own call sites.
 */
@AutographInternalApi
public fun Map<String, JsonElement>.deliverableTo(receiver: Any): Map<String, JsonElement> =
    if (isImplementedOutsideKotlin(receiver)) withNestedValuesAsJsonText() else this

/**
 * This tracker as Kotlin code can safely call: itself when it is written in Kotlin, and otherwise a
 * Kotlin tracker that forwards every call to it with [deliverableTo] applied.
 *
 * `AutographProvider` provides this as `LocalTracker`, so a Swift tracker given to it survives the
 * adopter's own `LocalTracker.current.track(...)` calls too, not only Autograph's. A Kotlin tracker
 * is returned as is, so its identity does not change. A Swift tracker that also adopts
 * [AwaitableCloseTracker] (by forwarding to an `Autograph { }` tracker, as ADR 0001 measured) keeps
 * that capability through the stand-in, so `closeAndAwait` on `LocalTracker.current` still reaches it.
 *
 * `@AutographInternalApi`: public only so `autograph-compose` can call it.
 */
@AutographInternalApi
public fun Tracker.callableFromKotlin(): Tracker = when {
    !isImplementedOutsideKotlin(this) -> this
    this is AwaitableCloseTracker -> AwaitableForeignTracker(this, this)
    else -> ForeignTracker(this)
}

/**
 * Stands in for a [Tracker] written in Swift or Objective-C, so it forwards every member, [close]
 * included: unlike a scoped view of a tracker, it is the same tracker under another type.
 */
@OptIn(AutographInternalApi::class)
internal open class ForeignTracker(private val delegate: Tracker) : Tracker {
    override fun track(name: String, properties: Map<String, JsonElement>, target: String?): Unit =
        delegate.track(name, properties.deliverableTo(delegate), target)

    override fun screen(name: String, properties: Map<String, JsonElement>): Unit =
        delegate.screen(name, properties.deliverableTo(delegate))

    override fun identify(userId: String, traits: Map<String, JsonElement>): Unit =
        delegate.identify(userId, traits.deliverableTo(delegate))

    override fun notifyForeground(): Unit = delegate.notifyForeground()
    override fun notifyBackground(): Unit = delegate.notifyBackground()
    override fun flush(): Unit = delegate.flush()
    override fun reset(): Unit = delegate.reset()
    override fun close(): Unit = delegate.close()
}

/**
 * A [ForeignTracker] for a delegate that also adopts [AwaitableCloseTracker]. Capabilities are probed
 * with `is`, so a stand-in that implemented only [Tracker] would turn the delegate's `closeAndAwait`
 * into [CloseResult.Unsupported] without closing anything.
 */
internal class AwaitableForeignTracker(
    delegate: Tracker,
    private val awaitable: AwaitableCloseTracker,
) : ForeignTracker(delegate), AwaitableCloseTracker {
    override suspend fun closeAndAwait(timeoutMillis: Long): CloseResult = awaitable.closeAndAwait(timeoutMillis)
}

/** Whether [receiver] is an object written in Swift or Objective-C rather than in Kotlin. */
internal expect fun isImplementedOutsideKotlin(receiver: Any): Boolean

internal fun Map<String, JsonElement>.withNestedValuesAsJsonText(): Map<String, JsonElement> {
    if (values.none { it.isNested() }) return this
    return JsonObject(mapValues { (_, value) -> if (value.isNested()) JsonPrimitive(value.toString()) else value })
}

private fun JsonElement.isNested(): Boolean = this is JsonObject || this is JsonArray
