package com.sphereon.oid.fed.kms.local.jwk

import com.nimbusds.jose.Algorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.sphereon.oid.fed.openapi.models.JwkWithPrivateKey
import java.util.*


actual fun generateKeyPair(): JwkWithPrivateKey {
    try {
        val ecKey: ECKey = ECKeyGenerator(Curve.P_256)
            .keyIDFromThumbprint(true)
            .algorithm(Algorithm("ES256"))
            .issueTime(Date())
            .generate()

        return JwkWithPrivateKey(
            d = ecKey.d.toString(),
            alg = ecKey.algorithm.name,
            crv = ecKey.curve.name,
            kid = ecKey.keyID,
            kty = ecKey.keyType.value,
            use = ecKey.keyUse?.value ?: "sig",
            x = ecKey.x.toString(),
            y = ecKey.y.toString()
        )

    } catch (e: Exception) {
        throw Exception("Couldn't generate the EC Key Pair: ${e.message}", e)
    }
}
