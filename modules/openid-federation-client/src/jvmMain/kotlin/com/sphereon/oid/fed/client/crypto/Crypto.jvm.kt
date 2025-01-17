package com.sphereon.oid.fed.client.crypto

import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.KeyType
import com.nimbusds.jwt.SignedJWT
import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.ParseException

actual fun cryptoService(): ICryptoService {
    return object : ICryptoService {
        override suspend fun verify(jwt: String, key: Jwk): Boolean {
            return try {
                val signedJWT = SignedJWT.parse(jwt)

                val nimbusJWK = JWK.parse(Json.encodeToString(key))

                val verifier: JWSVerifier = when (nimbusJWK.keyType) {
                    KeyType.RSA -> {
                        RSASSAVerifier(nimbusJWK.toRSAKey())
                    }

                    KeyType.EC -> {
                        val ecKey = nimbusJWK.toECKey()
                        if (!supportedCurve(ecKey.curve)) {
                            throw IllegalArgumentException("Unsupported EC curve: ${ecKey.curve}")
                        }
                        ECDSAVerifier(ecKey)
                    }

                    KeyType.OCT -> {
                        MACVerifier(nimbusJWK.toOctetSequenceKey())
                    }

                    else -> {
                        throw IllegalArgumentException("Unsupported key type: ${nimbusJWK.keyType}")
                    }
                }

                signedJWT.verify(verifier)
            } catch (e: ParseException) {
                false
            } catch (e: IllegalArgumentException) {
                false
            } catch (e: Exception) {
                false
            }
        }

        fun supportedCurve(curve: Curve): Boolean {
            return curve == Curve.P_256 || curve == Curve.P_384 || curve == Curve.P_521 || curve == Curve.SECP256K1
        }
    }
}
