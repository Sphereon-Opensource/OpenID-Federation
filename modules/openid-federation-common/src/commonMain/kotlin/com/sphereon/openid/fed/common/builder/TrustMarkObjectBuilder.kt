package com.sphereon.openid.fed.common.builder

import com.sphereon.openid.fed.openapi.models.TrustMarkPayload

class TrustMarkObjectBuilder {
    private var iss: String? = null
    private var sub: String? = null
    private var trustMarkType: String? = null
    private var iat: Double? = null
    private var logoUri: String? = null
    private var exp: Double? = null
    private var ref: String? = null
    private var delegation: String? = null
    private var trustMarkLifetime: Int? = null

    fun iss(iss: String) = apply { this.iss = iss }
    fun sub(sub: String) = apply { this.sub = sub }
    fun trustMarkType(trustMarkType: String) = apply { this.trustMarkType = trustMarkType }
    fun iat(iat: Double) = apply { this.iat = iat }
    fun logoUri(logoUri: String?) = apply { this.logoUri = logoUri }
    fun exp(exp: Double?) = apply { this.exp = exp }
    fun ref(ref: String?) = apply { this.ref = ref }
    fun delegation(delegation: String?) = apply { this.delegation = delegation }
    fun trustMarkLifetime(trustMarkLifetime: Int?) = apply { this.trustMarkLifetime = trustMarkLifetime }

    fun build(): TrustMarkPayload {
        return TrustMarkPayload(
            iss = iss ?: throw IllegalArgumentException("iss must be provided"),
            sub = sub ?: throw IllegalArgumentException("sub must be provided"),
            trustMarkType = trustMarkType ?: throw IllegalArgumentException("trustMarkType must be provided"),
            iat = iat ?: throw IllegalArgumentException("iat must be provided"),
            logoUri = logoUri,
            exp = exp,
            ref = ref,
            delegation = delegation,
            trustMarkLifetime = trustMarkLifetime
        )
    }
}
