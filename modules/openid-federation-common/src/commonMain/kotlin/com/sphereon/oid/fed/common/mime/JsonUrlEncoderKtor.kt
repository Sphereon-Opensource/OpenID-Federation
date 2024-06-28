package com.sphereon.oid.fed.common.mime

import io.ktor.http.*
import kotlinx.serialization.json.JsonElement

/**
 * Extension function for ParametersBuilder to append a URL encoded value.
 * Encodes the given data string and appends it to the ParametersBuilder with the specified name.
 *
 * @param name The name of the parameter
 * @param data The data to be URL encoded and appended
 * @return The ParametersBuilder with the appended value
 * @throws IllegalArgumentException if the name or data is empty
 */
fun ParametersBuilder.appendUrlEncodedValue(name: String, data: String): ParametersBuilder {
    require(name.isNotEmpty()) { "Parameter name cannot be empty" }
    require(data.isNotEmpty()) { "data cannot be empty" }

    this.append(name, data.toUrlEncodedValue())
    return this
}

/**
 * Extension function for ParametersBuilder to append a URL encoded JsonElement.
 * Converts the JsonElement to a string, URL encodes it, and appends it to the ParametersBuilder with the specified name.
 *
 * @param name The name of the parameter
 * @param jsonElement The JsonElement to be URL encoded and appended
 * @return The ParametersBuilder with the appended value
 * @throws IllegalArgumentException if the name is empty
 */
fun ParametersBuilder.appendUrlEncodedValue(name: String, jsonElement: JsonElement): ParametersBuilder {
    require(name.isNotEmpty()) { "Parameter name cannot be empty" }

    this.append(name, jsonElement.toString().toUrlEncodedValue())
    return this
}

/**
 * Extension function for Parameters to decode URL encoded values.
 * Decodes all URL encoded values in the Parameters and returns them as a Map.
 *
 * @return A Map containing the decoded parameter names and values
 */
fun Parameters.fromUrlEncodedValues(): Map<String, String> {
    return this.entries().mapNotNull {
        val value = it.value.firstOrNull()?.fromUrlEncodedValue()
        if (value != null) it.key to value else null
    }.toMap()
}

/**
 * Extension function for Parameters to decode URL encoded JSON values to JsonElements.
 * Decodes all URL encoded JSON values in the Parameters and returns them as a Map.
 *
 * @return A Map containing the decoded parameter names and JsonElements
 */
fun Parameters.fromUrlEncodedJsonValuesToJsonElements(): Map<String, JsonElement> {
    return this.entries().mapNotNull {
        val value = it.value.firstOrNull()?.fromUrlEncodedJsonValueToJsonElement()
        if (value != null) it.key to value else null
    }.toMap()
}

/**
 * Extension function for Parameters to get and decode a URL encoded value.
 * Retrieves the value for the specified name, decodes it, and returns the result.
 *
 * @param name The name of the parameter to retrieve
 * @return The decoded value
 * @throws IllegalArgumentException if the name is empty
 * @throws NoSuchElementException if no value is found for the specified name
 */
fun Parameters.getUrlEncodedValue(name: String): String {
    require(name.isNotEmpty()) { "Parameter name cannot be empty" }
    return this[name]?.fromUrlEncodedValue() ?: throw NoSuchElementException("No value found for key: $name")
}

/**
 * Extension function for Parameters to get and decode a URL encoded JSON value to a JsonElement.
 * Retrieves the value for the specified name, decodes it to a JsonElement, and returns the result.
 *
 * @param name The name of the parameter to retrieve
 * @return The decoded JsonElement
 * @throws IllegalArgumentException if the name is empty
 * @throws NoSuchElementException if no value is found for the specified name
 */
fun Parameters.getUrlEncodedJsonValueToJsonElement(name: String): JsonElement {
    require(name.isNotEmpty()) { "Parameter name cannot be empty" }
    return this[name]?.fromUrlEncodedJsonValueToJsonElement()
        ?: throw NoSuchElementException("No value found for key: $name")
}
