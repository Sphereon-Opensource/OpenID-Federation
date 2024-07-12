package com.sphereon.oid.fed.kms

import com.nimbusds.jose.jwk.JWK

interface AbstractKeyStore {
    fun importKey(key: JWK): Boolean
    fun getKey(kid: String): JWK?
    fun deleteKey(kid: String): Boolean
    fun listKeys(vararg kid: String): List<JWK>
    fun sign(kid: String, payload: String): String
}