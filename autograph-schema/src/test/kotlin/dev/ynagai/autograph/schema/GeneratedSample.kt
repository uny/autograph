package dev.ynagai.autograph.schema

// Hand-written stand-in for what generateTrackerExtensions() produces for the schema:
//
// { "events": [ { "name": "Recipe Saved", "properties": { "type": "object",
//   "properties": { "target": { "type": "string" }, "quantity": { "type": "integer" } },
//   "required": ["target"] } } ] }
//
// Kept byte-for-byte in sync with TrackerCodegenTest's assertion for the same schema — this file
// exists to prove that shape actually compiles and behaves correctly against the real Tracker API
// (see GeneratedSampleTest.kt), which a pure string-equality unit test on the generator cannot.

import dev.ynagai.autograph.Tracker
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public fun Tracker.trackRecipeSaved(
    target: String,
    quantity: Long? = null,
) {
    track(
        "Recipe Saved",
        buildJsonObject {
            quantity?.let { put("quantity", it) }
            put("target", target)
        },
    )
}

// Stand-in for a property name ("in") that's a Kotlin hard keyword — proves the backtick-escaped
// identifier generateTrackerExtensions() produces (see TrackerCodegenTest's
// backtickEscapesAParameterNameThatIsAKotlinHardKeyword) actually compiles and is usable both as a
// parameter declaration and as an expression (the `in`?.let { ... } / put(..., `in`) usage sites).
public fun Tracker.trackE(
    `in`: String,
) {
    track(
        "E",
        buildJsonObject {
            put("in", `in`)
        },
    )
}
