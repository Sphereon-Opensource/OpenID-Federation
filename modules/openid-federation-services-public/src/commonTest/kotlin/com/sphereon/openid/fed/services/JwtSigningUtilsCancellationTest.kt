package com.sphereon.openid.fed.services

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.error.IdkError
import com.sphereon.crypto.jose.jws.JwtCompactResult
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.JwsJsonFlattened
import com.sphereon.crypto.jose.jws.JwsJsonGeneral
import com.sphereon.crypto.jose.jws.JwsValidationResult
import com.sphereon.crypto.jose.jws.PreparedJwsObject
import com.sphereon.crypto.jose.jws.command.CreateJwsArgs
import com.sphereon.crypto.jose.jws.command.CreateJwsJsonArgs
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs
import com.sphereon.openid.fed.core.error.JwtCreationFailedError
import com.sphereon.openid.fed.openapi.models.BaseStatementJwks
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.JwtHeader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JwtSigningUtilsCancellationTest {
    @Test
    fun cancellationFromCompactSignerEscapesUnchangedAfterRealPayloadSerialization() = runBlocking {
        val cancellation = CancellationException("stop signing")
        val signer = ThrowingCompactJwtService(cancellation)

        var observedCancellation: CancellationException? = null
        try {
            signer.signPayload(
                entityConfigurationStatement(),
                entityStatementHeader(),
                kid = "persisted-key-kid",
                kmsKeyRef = "persisted-key-ref",
                kmsProviderId = "memory",
            )
        } catch (failure: CancellationException) {
            observedCancellation = failure
        }

        assertSame(cancellation, observedCancellation)
        val compactArgs = assertIs<CreateJwsArgs>(signer.observedCompactArgs)
        val encodedPayload = assertIs<JsonObject>(compactArgs.payload)
        assertEquals(JsonPrimitive("https://issuer.example"), encodedPayload["iss"])
        assertEquals(JsonPrimitive("https://entity.example"), encodedPayload["sub"])
    }

    @Test
    fun ordinaryCompactSignerFailureRemainsAControlledJwtError() = runBlocking {
        val signerFailure = IllegalStateException("controlled signer failure")
        val signer = ThrowingCompactJwtService(signerFailure)

        val result = signer.signPayload(
            entityConfigurationStatement(),
            entityStatementHeader(),
            kid = "persisted-key-kid",
            kmsKeyRef = "persisted-key-ref",
            kmsProviderId = "memory",
        )

        assertTrue(result.isErr, result.toString())
        val error = assertIs<JwtCreationFailedError>(result.error)
        assertEquals("controlled signer failure", error.reason)
        assertSame(signerFailure, error.exception)
    }

    private fun entityConfigurationStatement() = EntityConfigurationStatement(
        iss = "https://issuer.example",
        sub = "https://entity.example",
        exp = 4_102_444_800.0,
        iat = 1_700_000_000.0,
        jwks = BaseStatementJwks(propertyKeys = emptyList()),
    )

    private fun entityStatementHeader() = JwtHeader(
        alg = "ES256",
        kid = "persisted-key-kid",
        typ = "entity-statement+jwt",
    )

    private class ThrowingCompactJwtService(
        private val failure: Throwable,
    ) : JwtService {
        var observedCompactArgs: CreateJwsArgs? = null

        override val commands: JwtService.Commands
            get() = error("not used by signPayload")

        override fun assembleJwsGeneral(
            prepared: PreparedJwsObject,
            signatureBytes: ByteArray,
        ): JwsJsonGeneral = error("not used by signPayload")

        override fun assembleJwsFlattened(
            prepared: PreparedJwsObject,
            signatureBytes: ByteArray,
        ): JwsJsonFlattened = error("not used by signPayload")

        override fun assembleJwsCompact(
            prepared: PreparedJwsObject,
            signatureBytes: ByteArray,
        ): JwtCompactResult = error("not used by signPayload")

        override suspend fun prepareJws(
            args: CreateJwsJsonArgs,
        ): IdkResult<PreparedJwsObject, IdkError> = error("not used by signPayload")

        override suspend fun createJwsCompact(
            args: CreateJwsArgs,
        ): IdkResult<JwtCompactResult, IdkError> {
            observedCompactArgs = args
            throw failure
        }

        override suspend fun createJwsJsonFlattened(
            args: CreateJwsJsonArgs,
        ): IdkResult<JwsJsonFlattened, IdkError> = error("not used by signPayload")

        override suspend fun createJwsJsonGeneral(
            args: CreateJwsJsonArgs,
        ): IdkResult<JwsJsonGeneral, IdkError> = error("not used by signPayload")

        override suspend fun verifyJws(
            args: VerifyJwsArgs,
        ): IdkResult<JwsValidationResult, IdkError> = error("not used by signPayload")
    }
}
