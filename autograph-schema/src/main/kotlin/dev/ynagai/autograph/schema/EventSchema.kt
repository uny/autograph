package dev.ynagai.autograph.schema

/** A single event's shape, parsed from a tracking-plan JSON Schema document. */
public data class EventSchema(
    val name: String,
    val properties: List<PropertySchema>,
)

/** One property of an [EventSchema]. */
public data class PropertySchema(
    val name: String,
    val type: PropertyType,
    val required: Boolean,
)

/** The JSON Schema `"type"` values this generator understands. */
public enum class PropertyType(public val kotlinType: String) {
    STRING("String"),
    INTEGER("Long"),
    NUMBER("Double"),
    BOOLEAN("Boolean"),
}

/** Thrown by [parseTrackingPlan] when the document doesn't match the expected shape. */
public class TrackingPlanParseException(message: String) : Exception(message)
