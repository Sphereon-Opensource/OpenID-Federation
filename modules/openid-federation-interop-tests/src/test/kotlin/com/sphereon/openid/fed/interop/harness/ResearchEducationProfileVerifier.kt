package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jwt.SignedJWT
import java.time.Instant

/** R&E pilot-draft overlay. Generic OpenID Federation verification remains profile agnostic. */
internal object ResearchEducationProfileVerifier {
    fun verify(compact: String, expectedEntityId: String, now: Instant = Instant.now()): VerifiedStatement {
        val verified = PeerStatementVerifier.verify(compact, expectedEntityId, now)
        val metadata = SignedJWT.parse(compact).jwtClaimsSet.claims["metadata"]
        require(metadata is Map<*, *>) {
            "pinned R and E draft profile requires metadata to be a JSON object"
        }
        return verified
    }
}
