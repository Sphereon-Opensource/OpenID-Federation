package com.sphereon.oid.fed.server.admin.mappers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializerOrNull

internal fun Any?.toJsonElement(): JsonElement {
    val serializer = this?.let { Json.serializersModule.serializerOrNull(this::class.java) }

    return when {
        this == null -> JsonNull
        serializer != null -> Json.encodeToJsonElement(serializer, this)
        this is Map<*, *> -> toJsonElement()
        this is Array<*> -> toJsonElement()
        this is BooleanArray -> toJsonElement()
        this is ByteArray -> toJsonElement()
        this is CharArray -> toJsonElement()
        this is ShortArray -> toJsonElement()
        this is IntArray -> toJsonElement()
        this is LongArray -> toJsonElement()
        this is FloatArray -> toJsonElement()
        this is DoubleArray -> toJsonElement()
        this is UByteArray -> toJsonElement()
        this is UShortArray -> toJsonElement()
        this is UIntArray -> toJsonElement()
        this is ULongArray -> toJsonElement()
        this is Collection<*> -> toJsonElement()
        this is Boolean -> JsonPrimitive(this)
        this is Number -> JsonPrimitive(this)
        this is String -> JsonPrimitive(this)
        this is Enum<*> -> JsonPrimitive(this.name)
        this is Pair<*, *> -> JsonObject(
            mapOf(
                "first" to first.toJsonElement(),
                "second" to second.toJsonElement(),
            )
        )

        this is Triple<*, *, *> -> JsonObject(
            mapOf(
                "first" to first.toJsonElement(),
                "second" to second.toJsonElement(),
                "third" to third.toJsonElement(),
            )
        )

        else -> error("Can't serialize '$this' as it is of an unknown type")
    }
}

internal fun Map<*, *>.toJsonElement(): JsonElement {
    return buildJsonObject {
        forEach { (key, value) ->
            if (key !is String)
                error("Only string keys are supported for maps")

            put(key, value.toJsonElement())
        }
    }
}

internal fun Collection<*>.toJsonElement(): JsonElement = buildJsonArray {
    forEach { element ->
        add(element.toJsonElement())
    }
}

internal fun Array<*>.toJsonElement(): JsonElement = buildJsonArray {
    forEach { element ->
        add(element.toJsonElement())
    }
}

internal fun BooleanArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
internal fun ByteArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
internal fun CharArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it.toString())) } }
internal fun ShortArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
internal fun IntArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
internal fun LongArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
internal fun FloatArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
internal fun DoubleArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }

internal fun UByteArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
internal fun UShortArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
internal fun UIntArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
internal fun ULongArray.toJsonElement(): JsonElement = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
