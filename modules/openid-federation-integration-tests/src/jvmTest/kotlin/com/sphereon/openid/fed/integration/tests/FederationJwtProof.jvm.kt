package com.sphereon.openid.fed.integration.tests

import com.sphereon.crypto.jose.jws.JwtServiceImpl
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.client.crypto.verifyJwtSignature
import com.sphereon.openid.fed.integration.tests.platform.createIdkOauth2AsTestGraph
import com.sphereon.openid.fed.openapi.models.Jwk
import java.util.UUID

actual suspend fun verifyFederationTestJwt(jwt: String, key: Jwk): Boolean {
    val app = createIdkOauth2AsTestGraph(Unit, "oidf-public-jwt-proof", "test")
    val context = app.userContextManager.getAnonymous()
    val manager = context.sessionContextManager
    val id = UUID.randomUUID().toString()
    val session = manager.createOrGetFromId(id, principalType = PrincipalType.ANONYMOUS, makeActive = false)
    return try {
        (session.graph as JwtServiceImpl.Graph).jwtService.verifyJwtSignature(jwt, key)
    } finally {
        try { manager.destroyById(id) } finally { app.destroy() }
    }
}
