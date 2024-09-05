package com.sphereon.oid.fed.kms.local

import com.sphereon.oid.fed.kms.amazon.extensions.toJwkAdminDto
import com.sphereon.oid.fed.openapi.models.JWTHeader
import com.sphereon.oid.fed.openapi.models.Jwk
import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import kotlinx.serialization.json.JsonObject
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kms.KmsClient
import software.amazon.awssdk.services.kms.model.*
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*

class AmazonKms {

    private val kmsClient = KmsClient.builder().region(Region.US_WEST_2) // Replace with your desired region
        .build()

    fun generateKey(): JwkAdminDTO {
        val keyId = createKey()

        val request =
            GenerateDataKeyPairRequest.builder().keyId(keyId).keyPairSpec(DataKeyPairSpec.ECC_NIST_P256).build()
        val response = kmsClient.generateDataKeyPair(request)

        val publicKeyBytes = response.publicKey().asByteArray()
        val privateKeyBytes = response.privateKeyCiphertextBlob().asByteArray()

        val keyFactory = KeyFactory.getInstance("EC")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes)) as ECPublicKey
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)) as ECPrivateKey

        val x = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.w.affineX.toByteArray())
        val y = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.w.affineY.toByteArray())
        val d = Base64.getUrlEncoder().withoutPadding().encodeToString(privateKey.s.toByteArray())

        val jwk = Jwk(
            kty = "EC", crv = "P-256", kid = keyId,
            x = x, y = y, alg = "ES256", use = "sig", d = d
        )
        return jwk.toJwkAdminDto()
    }

    fun sign(header: JWTHeader, payload: JsonObject, keyId: String): String {
        val encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
            header.toString().toByteArray(
                StandardCharsets.UTF_8
            )
        )
        val encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toString().toByteArray(StandardCharsets.UTF_8))

        val messageBytes = (encodedHeader + "." + encodedPayload).toByteArray(StandardCharsets.UTF_8)

        val signingRequest = SignRequest.builder().keyId(keyId).message(SdkBytes.fromByteArray(messageBytes))
            .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256) // Adjust if needed
            .build()

        val signingResponse = kmsClient.sign(signingRequest)
        val signature =
            Base64.getUrlEncoder().withoutPadding().encodeToString(signingResponse.signature().asByteArray())

        return encodedHeader + "." + encodedPayload + "." + signature
    }

    fun verify(token: String, keyId: String): Boolean {
        try {
            val parts = token.split(".")
            if (parts.size != 3) {
                return false // Invalid token format
            }

            val header = parts[0]
            val payload = parts[1]
            val signature = parts[2]

            val verificationRequest = VerifyRequest.builder().keyId(keyId)
                .message(SdkBytes.fromString(header + "." + payload, StandardCharsets.UTF_8))
                .signature(SdkBytes.fromByteArray(Base64.getUrlDecoder().decode(signature)))
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256) // Adjust if needed
                .build()

            val verificationResponse = kmsClient.verify(verificationRequest)

            return verificationResponse.signatureValid()
        } catch (e: Exception) {
            return false
        }
    }

    private fun createKey(): String {
        val request = CreateKeyRequest.builder().keyUsage(KeyUsageType.SIGN_VERIFY)
            .build()

        val response = kmsClient.createKey(request)
        return response.keyMetadata().keyId()
    }
}