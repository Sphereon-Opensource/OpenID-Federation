package com.sphereon.openid.fed.services.command.registration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRegistrationError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyArgs
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommand
import com.sphereon.openid.fed.services.signPayload
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Provider-side Explicit Registration response (OpenID Federation for OpenID Connect 1.1 §12.2.3).
 *
 * The provider account signs, with its selected Federation Entity Key, an `explicit-registration-response+jwt`
 * Entity Statement about the client. The response carries the same Entity Types as the request, the registered
 * client metadata with its `client_id`, and an expiration no later than the Trust Chain the request was verified with.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<SignExplicitRegistrationResponseCommand>())
class SignExplicitRegistrationResponseCommandImpl(
    execution: SessionExecution,
    private val tenantContextResolver: TenantContextResolver,
    private val resolveSigningKey: ResolveAccountSigningKeyCommand,
    private val jwtService: JwtService,
) : TypedServiceCommandAdapter<SignExplicitRegistrationResponseArgs, ExplicitRegistrationResponse, FederationError>(
    commandId = SignExplicitRegistrationResponseCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<SignExplicitRegistrationResponseArgs>(),
    outputTypeToken = typeToken<ExplicitRegistrationResponse>(),
), SignExplicitRegistrationResponseCommand {

    override suspend fun doExecute(
        args: SignExplicitRegistrationResponseArgs,
        applyDuring: (SignExplicitRegistrationResponseArgs) -> SignExplicitRegistrationResponseArgs,
    ): IdkResult<ExplicitRegistrationResponse, FederationError> {
        val applied = applyDuring(args)
        val request = applied.request
        val client = request.clientEntityIdentifier
        fun rejected(reason: String) = federationErr<ExplicitRegistrationResponse>(InvalidRegistrationError(client, reason))

        val identifier = tenantContextResolver.resolveIdentifier(applied.accountId)
            ?: return federationErr(TenantNotFoundError(applied.accountId))
        if (identifier != request.providerEntityIdentifier) {
            return rejected("The signing account is not the provider the request was addressed to")
        }

        val registered = applied.registeredClientMetadata
        val clientId = registered.text("client_id") ?: return rejected("The registered metadata must contain client_id")
        val now = getCurrentEpochTimeSeconds()
        val expiresAt = applied.expiresAtEpochSeconds
        if (expiresAt <= now) return rejected("The registration expiration must be in the future")
        if (expiresAt > request.validUntilEpochSeconds) {
            return rejected("The registration must not outlive the Trust Chain the request was verified with")
        }
        if (registered.containsKey("client_secret")) {
            val secretExpiry = registered.epochSeconds("client_secret_expires_at")
                ?: return rejected("A provisioned client_secret requires client_secret_expires_at")
            if (secretExpiry != 0L && secretExpiry < expiresAt) {
                return rejected("The client_secret must not expire before the registration")
            }
        }

        val clientType = request.profile.clientEntityType
        val metadata = buildJsonObject {
            for (entityType in request.entityTypes) {
                val value = if (entityType == clientType) {
                    registered
                } else {
                    request.resolvedMetadata.entityTypeObject(entityType)
                        ?: return rejected("Entity Type $entityType has no Resolved Metadata to register")
                }
                put(entityType, value)
            }
        }
        val payload = buildJsonObject {
            put("iss", identifier)
            put("sub", client)
            put("aud", client)
            put("iat", now)
            put("exp", expiresAt)
            put("trust_anchor", request.trustAnchor)
            put("authority_hints", listOf(request.immediateSuperior).toJsonArray())
            put("metadata", metadata)
            if (applied.includeClientJwks) put("jwks", request.clientJwks)
        }

        val key = resolveSigningKey.execute(ResolveAccountSigningKeyArgs(applied.accountId, identifier))
        if (key.isErr) return federationErr(key.error)
        val signing = key.value
        val header = JwtHeader(alg = signing.alg, kid = signing.kid, typ = FederationRegistration.EXPLICIT_RESPONSE_TYP)
        val signed = jwtService.signPayload<JsonObject>(payload, header, signing.kid, signing.kmsKeyRef, signing.kms)
        if (signed.isErr) return federationErr(signed.error)

        return IdkResult.ok(
            ExplicitRegistrationResponse(
                contentType = FederationRegistration.EXPLICIT_RESPONSE_CONTENT_TYPE,
                body = signed.value,
                clientId = clientId,
                expiresAtEpochSeconds = expiresAt,
            )
        )
    }
}
