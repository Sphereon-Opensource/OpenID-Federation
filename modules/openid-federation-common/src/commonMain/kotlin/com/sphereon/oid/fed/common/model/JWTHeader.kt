package com.sphereon.oid.fed.common.model

import kotlinx.serialization.Serializable

import kotlinx.serialization.SerialName

@Serializable
data class JWTHeader(
    @SerialName("alg") val alg: String, // RS256
    @SerialName("kid") val kid: String, // B6EB8488CC84C41017134BC77F4132A0467CCC0E
    @SerialName("typ") val typ: String // entity-statement+jwt
)