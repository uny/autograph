package dev.ynagai.autograph

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The reserved `properties` key through which the library's own emit sites hand an event's
 * [EventMetadata] to the tracker (#287).
 *
 * The tracker built by [Autograph] **always removes** this key, before [AutographConfig.validator]
 * runs, so it never reaches a validator or a transport's `properties` — even when its value is
 * malformed. Only the fields listed on [EventMetadata] are read from it; anything else is discarded.
 * The value is a JSON object **encoded as a string**:
 *
 * ```json
 * "__autograph": "{\"kind\":\"impression\",\"impression\":{\"min_duration_ms\":500,\"min_fraction_visible\":0.5}}"
 * ```
 *
 * A string rather than a nested object because of Swift: Kotlin/Native hands a [JsonObject] value to
 * Objective-C as a dictionary, not as a `JsonElement`, so a [Tracker] implemented in Swift crashes the
 * process the moment it reads a nested object out of its `properties` — measured, and true of any
 * nested value, not only this one. A string crosses that bridge intact. The tracker also accepts the
 * object itself, unencoded, which is what a Kotlin caller would naturally write.
 *
 * It is a convention, not proof that the library produced the event: an app can put the same shape
 * in its own properties, and the tracker treats it the same way. Only the call-site `properties` are
 * read — a value under this key in [DefaultProperties] or in any scope (compose, `ScopeStack`, an
 * element's autocapture scope, a SwiftUI scope) is removed and ignored.
 *
 * A [Tracker] you implement yourself, such as a test fake, does not strip it, so a fake standing in
 * for the real tracker sees this key in the `properties` of events the library emits.
 */
public const val RESERVED_METADATA_KEY: String = "__autograph"

/**
 * The values [EventMetadata.kind] takes for events the library emits. String constants rather than an
 * enum, so a new kind is an addition rather than a break (ADR 0001 §2e).
 */
public object EventKinds {
    /** A tap: autocapture, `Modifier.trackClick`, the native tap captures, and `AutographButton`. */
    public const val CLICK: String = "click"

    /** An element became visible under `Modifier.trackImpression`'s thresholds. */
    public const val IMPRESSION: String = "impression"
}

/**
 * What the library knows about how an event was produced, beyond its name and properties (#287,
 * #243). It is carried in the envelope ([Envelope.metadata]) and serialized into the same
 * `context.instrumentation` block, never into `properties`.
 *
 * **Produced by the library, never constructed by callers** (ADR 0001 §2a), so it can gain fields as
 * additions. A plain `track`/`screen` call carries none; see [RESERVED_METADATA_KEY] for how an emit
 * site attaches it. No constructor parameter has a default: on the JVM, an all-defaults constructor
 * would get a public no-argument overload that Java callers could use.
 */
@ConsistentCopyVisibility
public data class EventMetadata internal constructor(
    /** The kind of interaction that produced the event, one of [EventKinds], or null when unknown. */
    val kind: String?,
    /** For an impression, how long the element had to stay visible before it was reported. */
    val impressionMinDurationMs: Long?,
    /** For an impression, the fraction of the element (0 to 1) that had to be visible. */
    val impressionMinFractionVisible: Double?,
) {
    /**
     * The fields this metadata adds to the `context.instrumentation` block. A field that is null is
     * omitted, and `impression` is omitted when both of its thresholds are.
     */
    public fun toJson(): JsonObject = buildJsonObject {
        kind?.let { put("kind", it) }
        if (impressionMinDurationMs != null || impressionMinFractionVisible != null) {
            putJsonObject("impression") {
                impressionMinDurationMs?.let { put("min_duration_ms", it) }
                impressionMinFractionVisible?.let { put("min_fraction_visible", it) }
            }
        }
    }
}

/**
 * A [Transport] that stamps in its own pipeline ([Transport.stampsInPipeline]) and can add an
 * event's [EventMetadata] to the envelope it stamps there (ADR 0001 §2c: a capability the core probes
 * with `is`, because [Transport]'s own member set is frozen).
 *
 * The core calls [track] here instead of [Transport.track] only for an event that carries metadata;
 * every other event still goes through [Transport.track]. A transport the core stamps for never needs
 * this: the core puts the metadata on the [Envelope] it passes in.
 *
 * A pipeline transport that does not implement it still receives the event, through
 * [Transport.track], without the metadata, and the tracker logs that once through
 * [AutographConfig.logger].
 *
 * There is no `screen` counterpart: a screen view carries no metadata. If one ever does, it gets a
 * second capability interface, since this one's member set is frozen once it ships (ADR 0001 §2c).
 */
public interface MetadataAwareTransport {
    /**
     * Delivers an event exactly as [Transport.track] would, and adds [metadata] to the envelope the
     * transport stamps for this event. [properties] no longer contain [RESERVED_METADATA_KEY].
     */
    public fun track(name: String, properties: Map<String, JsonElement>, envelope: Envelope?, metadata: EventMetadata)
}

/**
 * Splits [RESERVED_METADATA_KEY] out of [properties]: the properties without it, and the metadata its
 * allow-listed fields parse into (null when there are none). The key is removed whatever its value;
 * a field of the wrong type or out of range is dropped on its own.
 */
internal fun extractMetadata(properties: JsonObject): Pair<JsonObject, EventMetadata?> {
    val raw = properties[RESERVED_METADATA_KEY] ?: return properties to null
    return JsonObject(properties - RESERVED_METADATA_KEY) to parseMetadata(raw)
}

private fun parseMetadata(raw: JsonElement): EventMetadata? {
    val obj = raw as? JsonObject ?: raw.stringOrNull()?.let(::parseObjectOrNull) ?: return null
    val kind = obj["kind"].stringOrNull()?.takeIf { it.isNotEmpty() }
    val impression = obj["impression"] as? JsonObject
    val minDurationMs = impression?.get("min_duration_ms").numberOrNull()?.longOrNull?.takeIf { it >= 0 }
    val minFractionVisible = impression?.get("min_fraction_visible").numberOrNull()?.doubleOrNull
        ?.takeIf { it in 0.0..1.0 }
    if (kind == null && minDurationMs == null && minFractionVisible == null) return null
    return EventMetadata(kind, minDurationMs, minFractionVisible)
}

private fun parseObjectOrNull(json: String): JsonObject? =
    try {
        Json.parseToJsonElement(json) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.numberOrNull(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { !it.isString }

/**
 * These properties with the library's [EventMetadata] written on top under [RESERVED_METADATA_KEY],
 * for Autograph's own emit sites. Apply it last, after any scope merge, so the emit site's value is
 * the one the tracker reads; whatever was under the key before is replaced.
 *
 * Built from [EventMetadata.toJson], so the shape the tracker parses back and the shape written to
 * `context.instrumentation` cannot drift apart. Encoded as a string, for the reason given on
 * [RESERVED_METADATA_KEY].
 */
@AutographInternalApi
public fun Map<String, JsonElement>.withEventMetadata(
    kind: String,
    impressionMinDurationMs: Long? = null,
    impressionMinFractionVisible: Double? = null,
): JsonObject = JsonObject(
    this + (
        RESERVED_METADATA_KEY to
            JsonPrimitive(EventMetadata(kind, impressionMinDurationMs, impressionMinFractionVisible).toJson().toString())
        ),
)
