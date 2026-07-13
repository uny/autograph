package dev.ynagai.autograph.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a tracking-plan document into [EventSchema]s. Only a minimal subset of JSON Schema is
 * understood — this is the initial slice of the codegen engine (see the `autograph-schema`
 * module's README section for what's deferred). Expected shape:
 *
 * ```json
 * {
 *   "events": [
 *     {
 *       "name": "Recipe Saved",
 *       "properties": {
 *         "type": "object",
 *         "properties": { "target": { "type": "string" }, "quantity": { "type": "integer" } },
 *         "required": ["target"]
 *       }
 *     }
 *   ]
 * }
 * ```
 *
 * Supported property `"type"`s: `string`, `integer`, `number`, `boolean`. An event's `"properties"`
 * may be omitted entirely for an event with no properties. Nested objects/arrays, `"enum"`,
 * `"$ref"`, and `oneOf`/`anyOf`/`allOf` are not supported and are rejected with
 * [TrackingPlanParseException].
 */
public fun parseTrackingPlan(json: String): List<EventSchema> {
    val root = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (e: Exception) {
        throw TrackingPlanParseException("not a valid JSON object: ${e.message}")
    }
    val events = root["events"]?.jsonArray
        ?: throw TrackingPlanParseException("missing top-level \"events\" array")
    val schemas = events.map { parseEvent(it.jsonObject) }

    val duplicateNames = schemas.groupBy { it.name }.filterValues { it.size > 1 }.keys
    if (duplicateNames.isNotEmpty()) {
        throw TrackingPlanParseException("duplicate event name(s): ${duplicateNames.joinToString()}")
    }
    val duplicateFunctionNames = schemas.groupBy { "track" + it.name.toPascalCase() }.filterValues { it.size > 1 }
    if (duplicateFunctionNames.isNotEmpty()) {
        val (functionName, colliding) = duplicateFunctionNames.entries.first()
        throw TrackingPlanParseException(
            "event names ${colliding.joinToString { "\"${it.name}\"" }} all generate the same " +
                "function name \"$functionName\" — rename one of them",
        )
    }
    return schemas
}

private fun parseEvent(event: JsonObject): EventSchema {
    val name = event["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        ?: throw TrackingPlanParseException("event is missing a non-blank \"name\"")
    val properties = event["properties"]?.jsonObject?.let { parseProperties(name, it) } ?: emptyList()
    return EventSchema(name, properties)
}

private fun parseProperties(eventName: String, schema: JsonObject): List<PropertySchema> {
    val propsObject = schema["properties"]?.jsonObject ?: return emptyList()
    val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

    val properties = propsObject.entries.map { (propName, propSchema) ->
        val typeName = propSchema.jsonObject["type"]?.jsonPrimitive?.content
            ?: throw TrackingPlanParseException("event \"$eventName\": property \"$propName\" is missing \"type\"")
        val type = PropertyType.entries.find { it.name.equals(typeName, ignoreCase = true) }
            ?: throw TrackingPlanParseException(
                "event \"$eventName\": property \"$propName\" has unsupported type \"$typeName\" " +
                    "(supported: string, integer, number, boolean)",
            )
        PropertySchema(propName, type, required = propName in required)
    }

    val duplicateParams = properties.groupBy { it.name.toCamelCase() }.filterValues { it.size > 1 }
    if (duplicateParams.isNotEmpty()) {
        val (paramName, colliding) = duplicateParams.entries.first()
        throw TrackingPlanParseException(
            "event \"$eventName\": properties ${colliding.joinToString { "\"${it.name}\"" }} all generate " +
                "the same parameter name \"$paramName\" — rename one of them",
        )
    }
    return properties
}
