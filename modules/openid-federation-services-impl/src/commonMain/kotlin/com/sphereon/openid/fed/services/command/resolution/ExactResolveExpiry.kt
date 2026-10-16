package com.sphereon.openid.fed.services.command.resolution

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Compare original finite JSON NumericDate decimals without a binary floating-point comparison. */
internal class ExactResolveExpiry private constructor(
    val json: JsonPrimitive,
    private val sign: Int,
    private val magnitude: Long,
    private val digits: String,
) : Comparable<ExactResolveExpiry> {
    override fun compareTo(other: ExactResolveExpiry): Int {
        if (sign != other.sign) return sign.compareTo(other.sign)
        if (sign == 0) return 0
        val exponent = magnitude.compareTo(other.magnitude)
        if (exponent != 0) return exponent * sign
        for (index in 0 until maxOf(digits.length, other.digits.length)) {
            val comparison = (digits.getOrNull(index) ?: '0').compareTo(other.digits.getOrNull(index) ?: '0')
            if (comparison != 0) return comparison * sign
        }
        return 0
    }

    /** The legacy Double DTO stays compatible and must never round later than the exact signed expiry. */
    fun conservativeDouble(): Double {
        val value = requireNotNull(json.doubleOrNull)
        val roundTrip = requireNotNull(parse(JsonPrimitive(value)))
        if (roundTrip <= this) return value
        return when {
            value > 0.0 -> Double.fromBits(value.toBits() - 1)
            value < 0.0 -> Double.fromBits(value.toBits() + 1)
            else -> -Double.MIN_VALUE
        }
    }

    companion object {
        private val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
        fun parse(element: JsonElement?): ExactResolveExpiry? {
            val json = element as? JsonPrimitive ?: return null
            if (json.isString || !number.matches(json.content) || json.doubleOrNull?.isFinite() != true) return null
            val raw = json.content
            val exponentIndex = raw.indexOfFirst { it == 'e' || it == 'E' }
            val mantissa = if (exponentIndex < 0) raw else raw.substring(0, exponentIndex)
            val exponent = if (exponentIndex < 0) 0L else raw.substring(exponentIndex + 1).toLongOrNull() ?: return null
            val dot = mantissa.indexOf('.')
            val fractionLength = if (dot < 0) 0 else mantissa.length - dot - 1
            val digits = mantissa.removePrefix("-").replace(".", "").trimStart('0')
            if (digits.isEmpty()) return ExactResolveExpiry(json, 0, 0, "0")
            val offset = digits.length.toLong() - fractionLength - 1
            if ((offset > 0 && exponent > Long.MAX_VALUE - offset) || (offset < 0 && exponent < Long.MIN_VALUE - offset)) return null
            return ExactResolveExpiry(json, if (raw.startsWith('-')) -1 else 1, exponent + offset, digits.trimEnd('0'))
        }
    }
}
