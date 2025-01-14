package com.sphereon.oid.fed.common.mime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

private val qpAllowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~".toSet()

/**
 * URL encode a String.
 * Converts characters not allowed in URL query parameters to their percent-encoded equivalents.
 * input   an input string
 * @return URL encoded String
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun urlEncodeValue(input: String): String {
    return buildString {
        input.forEach { char ->
            if (char in qpAllowedChars) {
                append(char)
            } else {
                append('%')
                append(char.code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

/**
 * Extension function to URL encode a String.
 * Converts characters not allowed in URL query parameters to their percent-encoded equivalents.
 *
 * @return URL encoded String
 */
fun String.toUrlEncodedValue(): String {
    return urlEncodeValue(this)
}

/**
 * Extension function to URL encode a JsonElement.
 * Converts the JsonElement to a JSON String and then URL encodes it.
 *
 * @return URL encoded String representation of the JsonElement
 */
fun JsonElement.toUrlEncodedValue(): String {
    return this.toString().toUrlEncodedValue()
}

/**
 * Inline function to URL encode any serializable object.
 * Converts the object to a JSON string and then URL encodes it.
 *
 * @return URL encoded JSON String representation of the object
 */
inline fun <reified T> T.toUrlEncodedJsonValue(): String {
    return Json.encodeToString(this).toUrlEncodedValue()
}

/**
 * Function to URL encode any serializable object using a provided serializer.
 * Converts the object to a JSON string using the serializer and then URL encodes it.
 *
 * @param serializer The serializer to use for converting the object to a JSON string
 * @return URL encoded JSON String representation of the object
 */
fun <T> T.toUrlEncodedJsonValue(serializer: KSerializer<T>): String {
    return Json.encodeToString(serializer, this).toUrlEncodedValue()
}

/**
 * Extension function to decode a URL encoded String.
 * Converts percent-encoded characters back to their original form.
 *
 * input   An URL encoded input string
 * @return Decoded String
 */
@ExperimentalJsExport
@JsExport
fun urlDecodeValue(input: String): String {
    return buildString {
        var i = 0
        while (i < input.length) {
            when (val char = input[i]) {
                '%' -> {
                    if (i + 2 >= input.length) {
                        throw IllegalArgumentException("Incomplete percent encoding at position $i")
                    }
                    append(input.substring(i + 1, i + 3).toInt(16).toChar())
                    i += 3
                }

                else -> {
                    append(char)
                    i++
                }
            }
        }
    }
}

/**
 * Extension function to decode a URL encoded String.
 * Converts percent-encoded characters back to their original form.
 *
 * @return Decoded String
 */
@OptIn(ExperimentalJsExport::class)
fun String.fromUrlEncodedValue(): String {
    return urlDecodeValue(this)
}

/**
 * Extension function to decode a URL encoded JSON String to a JsonElement.
 * Decodes the URL encoded String and parses it to a JsonElement.
 *
 * @return Decoded JsonElement
 */
fun String.fromUrlEncodedJsonValueToJsonElement(): JsonElement {
    val decodedString = this.fromUrlEncodedValue()
    return Json.parseToJsonElement(decodedString)
}

/**
 * Inline function to decode a URL encoded JSON String to an object of type T.
 * Decodes the URL encoded String and deserializes it to an object of type T.
 *
 * @return Deserialized object of type T
 */
inline fun <reified T> String.fromUrlEncodedJsonValue(): T {
    val decodedString = this.fromUrlEncodedValue()
    return Json.decodeFromString(decodedString)
}

/**
 * Function to decode a URL encoded JSON String to an object of type T using a provided serializer.
 * Decodes the URL encoded String and deserializes it to an object of type T using the serializer.
 *
 * @param serializer The serializer to use for deserializing the JSON string
 * @return Deserialized object of type T
 */
fun <T> String.fromUrlEncodedJsonValue(serializer: KSerializer<T>): T {
    val decodedString = this.fromUrlEncodedValue()
    return Json.decodeFromString(serializer, decodedString)
}
