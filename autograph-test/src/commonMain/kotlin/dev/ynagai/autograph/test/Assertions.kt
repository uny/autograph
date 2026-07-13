package dev.ynagai.autograph.test

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Thrown by the `assert*` functions in this file on a failed assertion. */
public class EventAssertionError(message: String) : AssertionError(message)

/**
 * Asserts that a `track` event named [name] was recorded, and returns it.
 *
 * [properties], when given, are matched by **containment** by default: every key/value pair in
 * [properties] must be present in the recorded event's properties, and extra recorded properties
 * are ignored. Pass `exact = true` to require the recorded properties to match [properties]
 * exactly, with no extras. Values may be plain Kotlin types (`String`, `Number`, `Boolean`, `null`,
 * `Map`, `List`) or [JsonElement] directly.
 */
public fun InMemoryTestTransport.assertEventFired(
    name: String,
    properties: Map<String, Any?>? = null,
    exact: Boolean = false,
): InMemoryTestTransport.RecordedEvent = assertFired(InMemoryTestTransport.Kind.TRACK, name, properties, exact)

/** Asserts that a `screen` event named [name] was recorded, and returns it. See [assertEventFired] for [properties]/[exact]. */
public fun InMemoryTestTransport.assertScreenFired(
    name: String,
    properties: Map<String, Any?>? = null,
    exact: Boolean = false,
): InMemoryTestTransport.RecordedEvent = assertFired(InMemoryTestTransport.Kind.SCREEN, name, properties, exact)

/** Asserts that `identify(userId, ...)` was recorded, and returns it. See [assertEventFired] for [traits]/[exact]. */
public fun InMemoryTestTransport.assertIdentifyFired(
    userId: String,
    traits: Map<String, Any?>? = null,
    exact: Boolean = false,
): InMemoryTestTransport.RecordedEvent = assertFired(InMemoryTestTransport.Kind.IDENTIFY, userId, traits, exact)

/** Asserts that no `track` event named [name] was ever recorded. */
public fun InMemoryTestTransport.assertEventNotFired(name: String) {
    val fired = events.filter { it.kind == InMemoryTestTransport.Kind.TRACK && it.name == name }
    if (fired.isNotEmpty()) {
        throw EventAssertionError(
            "expected no track event named \"$name\" to fire, but it fired ${fired.size} time(s) " +
                "with properties ${fired.map { it.properties }}",
        )
    }
}

/** Asserts that events fired in exactly this [names] order (across all kinds — track/screen/identify). */
public fun InMemoryTestTransport.assertOrder(vararg names: String) {
    val actual = events.map { it.name }
    if (actual != names.toList()) {
        throw EventAssertionError("expected event order ${names.toList()}, but got $actual")
    }
}

/**
 * Asserts that both [dev.ynagai.autograph.Envelope.seq] (gapless, +1 within each contiguous run of
 * events sharing a session id — a session rotation restarts it at 1, which is expected and not a
 * gap) and [dev.ynagai.autograph.Envelope.globalSeq] (gapless, +1 across the whole device lifetime,
 * independent of session) hold wherever each is non-null. Depending on `AutographConfig.sequence`,
 * only one, both, or neither may be stamped (`SequenceMode.None` stamps neither, so nothing is
 * checked); events with no envelope are skipped.
 */
public fun InMemoryTestTransport.assertNoSeqGaps() {
    var previousSessionId: String? = null
    var previousSeq: Long? = null
    var previousGlobalSeq: Long? = null
    for ((index, event) in events.withIndex()) {
        val envelope = event.envelope ?: continue
        envelope.seq?.let { seq ->
            if (envelope.session.id == previousSessionId && previousSeq != null && seq != previousSeq + 1) {
                throw EventAssertionError(
                    "seq gap at event #$index (\"${event.name}\"): expected ${previousSeq + 1}, got $seq",
                )
            }
            previousSeq = seq
        }
        envelope.globalSeq?.let { globalSeq ->
            if (previousGlobalSeq != null && globalSeq != previousGlobalSeq + 1) {
                throw EventAssertionError(
                    "global_seq gap at event #$index (\"${event.name}\"): expected ${previousGlobalSeq + 1}, got $globalSeq",
                )
            }
            previousGlobalSeq = globalSeq
        }
        previousSessionId = envelope.session.id
    }
}

/**
 * Asserts every recorded event with an envelope belongs to the same session (no rotation
 * occurred), and returns that session id.
 */
public fun InMemoryTestTransport.assertSingleSession(): String {
    val sessionIds = events.mapNotNull { it.envelope?.session?.id }.distinct()
    if (sessionIds.size > 1) {
        throw EventAssertionError("expected a single session, but events span ${sessionIds.size}: $sessionIds")
    }
    return sessionIds.singleOrNull()
        ?: throw EventAssertionError("no recorded event carried an envelope")
}

private fun InMemoryTestTransport.assertFired(
    kind: InMemoryTestTransport.Kind,
    name: String,
    properties: Map<String, Any?>?,
    exact: Boolean,
): InMemoryTestTransport.RecordedEvent {
    val candidates = events.filter { it.kind == kind && it.name == name }
    if (candidates.isEmpty()) {
        throw EventAssertionError(
            "expected a $kind event named \"$name\" to fire, but it never did. " +
                "Fired events: ${events.ifEmpty { null }?.joinToString { "${it.kind} \"${it.name}\"" } ?: "(none)"}",
        )
    }
    val match = candidates.firstOrNull { matchesProperties(it.properties, properties, exact) }
    return match ?: throw EventAssertionError(
        "a $kind event named \"$name\" fired, but none matched properties=$properties (exact=$exact). " +
            "Recorded properties: ${candidates.map { it.properties }}",
    )
}

private fun matchesProperties(actual: JsonObject, expected: Map<String, Any?>?, exact: Boolean): Boolean {
    if (expected == null) return true
    val expectedJson = expected.mapValues { it.value.toJsonElement() }
    if (exact && actual.size != expectedJson.size) return false
    return expectedJson.all { (key, value) -> actual[key] == value }
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}
