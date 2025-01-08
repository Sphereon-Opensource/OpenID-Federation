package com.sphereon.oid.fed.common.builder

import com.sphereon.oid.fed.openapi.models.TrustMarkObject

class TrustMarkObjectBuilder {
    private var iss: String? = null
    private var sub: String? = null
    private var id: String? = null
    private var iat: Int? = null
    private var logoUri: String? = null
    private var exp: Int? = null
    private var ref: String? = null
    private var delegation: String? = null

    fun iss(iss: String) = apply { this.iss = iss }
    fun sub(sub: String) = apply { this.sub = sub }
    fun id(id: String) = apply { this.id = id }
    fun iat(iat: Int) = apply { this.iat = iat }
    fun logoUri(logoUri: String?) = apply { this.logoUri = logoUri }
    fun exp(exp: Int?) = apply { this.exp = exp }
    fun ref(ref: String?) = apply { this.ref = ref }
    fun delegation(delegation: String?) = apply { this.delegation = delegation }

    fun build(): TrustMarkObject {
        return TrustMarkObject(
            iss = iss ?: throw IllegalArgumentException("iss must be provided"),
            sub = sub ?: throw IllegalArgumentException("sub must be provided"),
            id = id ?: throw IllegalArgumentException("id must be provided"),
            iat = iat ?: throw IllegalArgumentException("iat must be provided"),
            logoUri = logoUri,
            exp = exp,
            ref = ref,
            delegation = delegation
        )
    }
}
