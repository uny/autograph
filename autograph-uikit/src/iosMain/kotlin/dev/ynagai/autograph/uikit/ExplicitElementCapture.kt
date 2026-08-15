package dev.ynagai.autograph.uikit

import dev.ynagai.autograph.EmptyJsonObject
import dev.ynagai.autograph.Tracker
import dev.ynagai.autograph.context.ScopeStack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Explicit click instrumentation for **Swift** callers — the receiving end of the `AutographUI`
 * Swift product's `AutographButton` and `autograph.track(_:)`.
 *
 * ## Why this exists at all
 *
 * [Tracker.track] takes a [JsonObject], and **Swift cannot build one**. `Autograph.xcframework`
 * exports core/context/uikit/segment but not `kotlinx-serialization-json`, so `JsonPrimitive` has no
 * Objective-C counterpart and `JsonElement` arrives as an opaque class with no initializer: a Swift
 * caller can pass an empty properties dictionary and nothing else. Exporting the serialization
 * library instead would put a third party's whole surface into this SDK's public Objective-C API, so
 * the conversion lives here, on values Swift can actually express.
 *
 * ## What it merges, and from where
 *
 * The precedence is the one the Compose path applies (`withScreenContext` plus the scope decorator),
 * so an event means the same thing on both platforms:
 *
 * 1. [scope] — the caller's ambient scope, lowest precedence
 * 2. [properties] — the call site's own values, which win over the scope
 * 3. `target`, then `screen` / `section` — reserved keys written on top
 *
 * **[scope] is passed in, not read from [scopeStack], and that is deliberate.**
 * `ScopeStack.resolveScope()` drops sibling frames that are neither's ancestor, because an
 * autocaptured tap carries no evidence of *which* sibling it hit. A list whose rows each own a scope
 * is exactly that shape, so reading the stack here would silently drop the row's scope — even though
 * an explicit call site knows precisely which row it is. Swift therefore accumulates the scope
 * lexically (a SwiftUI `Environment` value, the analogue of Compose's `ScopedTracker`) and hands it
 * over per call. Screen and section *are* read from the stack: at most one screen is current, so the
 * ambiguity that forces the drop cannot arise, and the stack is the only place a SwiftUI screen name
 * lives (see [AutographScreenCapture]).
 *
 * That split is why [ScopeStack]'s "read this only from auto-capture code" note does not apply here:
 * the part this reads dynamically is the part that has no ambiguity, and the part that would be
 * ambiguous is supplied lexically.
 *
 * ## Threading and failure
 *
 * Main thread, like the rest of the SwiftUI-facing surface. **Nothing here throws into Swift** — a
 * Kotlin exception unwinding into a Swift caller with no `@Throws` crashes the app, and an analytics
 * event is never worth a crash.
 */
public class AutographElementCapture(
    private val tracker: Tracker,
    private val scopeStack: ScopeStack,
) {

    /**
     * Records a click named [name] carrying [properties], attributed with [scope] and the current
     * screen/section.
     *
     * [target] identifies which element fired it and is written under the reserved `target` key,
     * matching [Tracker.track]'s own contract.
     */
    public fun clicked(
        name: String,
        properties: Map<String, String> = emptyMap(),
        scope: Map<String, String> = emptyMap(),
        target: String? = null,
    ) {
        emit(name, properties.toJsonObject(), scope, target)
    }

    /**
     * [clicked] for properties that are not all strings — numbers, booleans, nested objects — passed
     * as a JSON object literal. The Swift side has no way to express a [JsonObject], and a
     * `[String: String]` dictionary cannot carry them, so this is the escape hatch; it mirrors the
     * `JsonObject` overload the Compose API offers.
     *
     * [propertiesJson] must parse to a JSON **object**. Anything else — malformed text, or a valid
     * JSON array/number/string — is dropped to empty properties rather than throwing or guessing,
     * and the event is still recorded: losing the properties of an event is bad, losing the event
     * (or crashing Swift) is worse.
     */
    public fun clickedJson(
        name: String,
        propertiesJson: String,
        scope: Map<String, String> = emptyMap(),
        target: String? = null,
    ) {
        emit(name, parseJsonObject(propertiesJson), scope, target)
    }

    private fun emit(
        name: String,
        properties: JsonObject,
        scope: Map<String, String>,
        target: String?,
    ) {
        try {
            // Scope underneath, call-site properties on top: a scope entry fills a key the call site
            // did not set, and the call site wins a clash. Identical to `mergeScope` in the Compose
            // path, deliberately.
            val scoped = if (scope.isEmpty()) properties else JsonObject(scope.toJsonObject() + properties)
            val context = scopeStack.current()
            // Only screen/section from the stack — never its scope. See the class kdoc.
            var result = scoped
            context.screen?.let { result = JsonObject(result + ("screen" to JsonPrimitive(it))) }
            context.section?.let { result = JsonObject(result + ("section" to JsonPrimitive(it))) }
            tracker.track(name, result, target)
        } catch (_: Throwable) {
            // Never unwind into Swift. A dropped event is recoverable; a crash in someone's app
            // because their analytics failed is not.
        }
    }
}

private fun Map<String, String>.toJsonObject(): JsonObject =
    if (isEmpty()) EmptyJsonObject else JsonObject(mapValues { (_, value) -> JsonPrimitive(value) })

private fun parseJsonObject(json: String): JsonObject =
    try {
        Json.parseToJsonElement(json) as? JsonObject ?: EmptyJsonObject
    } catch (_: Throwable) {
        EmptyJsonObject
    }
