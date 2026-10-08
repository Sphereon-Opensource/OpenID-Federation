package com.sphereon.openid.fed.services.command.registration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidMetadataError
import com.sphereon.openid.fed.core.error.InvalidRegistrationError
import com.sphereon.openid.fed.core.error.TenantNotFoundError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.services.command.entityConfiguration.FindEntityConfigurationByAccountArgs
import com.sphereon.openid.fed.services.command.entityConfiguration.FindEntityConfigurationByAccountCommand
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyArgs
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommand
import com.sphereon.openid.fed.services.signPayload
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Client-side Explicit Registration request (OpenID Federation for OpenID Connect 1.1 §12.2.1).
 *
 * The provider's Trust Chain and Resolved Metadata are established first; the request is refused when the provider
 * does not support Explicit Registration. Every requested `authority_hints` entry must lead to an accepted Trust
 * Anchor. The client account's Entity Configuration is then issued to the provider with the requested metadata and
 * hints and signed with the account's selected Federation Entity Key.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateExplicitRegistrationRequestCommand>())
class CreateExplicitRegistrationRequestCommandImpl(
    execution: SessionExecution,
    resolveTrustChain: ResolveTrustChainCommand,
    verifyTrustChain: VerifyTrustChainCommand,
    getEntityConfiguration: GetEntityConfigurationCommand,
    private val tenantContextResolver: TenantContextResolver,
    private val findEntityConfiguration: FindEntityConfigurationByAccountCommand,
    private val resolveSigningKey: ResolveAccountSigningKeyCommand,
    private val jwtService: JwtService,
) : TypedServiceCommandAdapter<CreateExplicitRegistrationRequestArgs, ExplicitRegistrationRequest, FederationError>(
    commandId = CreateExplicitRegistrationRequestCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateExplicitRegistrationRequestArgs>(),
    outputTypeToken = typeToken<ExplicitRegistrationRequest>(),
), CreateExplicitRegistrationRequestCommand {

    private val chains = RegistrationTrustChains(resolveTrustChain, verifyTrustChain, getEntityConfiguration)

    override suspend fun doExecute(
        args: CreateExplicitRegistrationRequestArgs,
        applyDuring: (CreateExplicitRegistrationRequestArgs) -> CreateExplicitRegistrationRequestArgs,
    ): IdkResult<ExplicitRegistrationRequest, FederationError> {
        val applied = applyDuring(args)
        val provider = applied.providerEntityIdentifier
        val profile = applied.profile
        val identifier = tenantContextResolver.resolveIdentifier(applied.accountId)
            ?: return federationErr(TenantNotFoundError(applied.accountId))
        fun rejected(reason: String) = federationErr<ExplicitRegistrationRequest>(InvalidRegistrationError(identifier, reason))

        if (applied.authorityHints.isEmpty()) return rejected("At least one authority_hints entry is required")
        if (applied.authorityHints.toSet().size != applied.authorityHints.size) return rejected("authority_hints must not repeat")
        if (applied.metadata.entityTypeObject(profile.clientEntityType) == null) {
            return federationErr(InvalidMetadataError(identifier, "The metadata to register has no ${profile.clientEntityType}"))
        }

        val providerChain = chains.resolve(provider, applied.trustAnchors, startingAuthorityHints = null)
        if (providerChain.isErr) return federationErr(providerChain.error)
        val providerResolved = resolveRegistrationMetadata(provider, providerChain.value.payloads[0], providerChain.value.payloads.drop(1))
        if (providerResolved.isErr) return federationErr(providerResolved.error)
        val providerMetadata = providerResolved.value.entityTypeObject(profile.providerEntityType)
            ?: return federationErr(InvalidMetadataError(provider, "The provider has no ${profile.providerEntityType} metadata"))
        val registrationTypes = providerMetadata["client_registration_types_supported"].stringList().orEmpty()
        if (FederationRegistration.EXPLICIT !in registrationTypes) {
            return rejected("The provider does not support Explicit Registration")
        }
        val endpoint = providerMetadata.text("federation_registration_endpoint")
            ?: return rejected("The provider publishes no federation_registration_endpoint")
        if (!endpoint.startsWith("https://")) return rejected("The federation_registration_endpoint must use https")
        val selectedTrustAnchor = providerChain.value.trustAnchor
        val selectedAnchors = applied.trustAnchors.filter { it.entityIdentifier == selectedTrustAnchor }

        val ownConfiguration = findEntityConfiguration.execute(FindEntityConfigurationByAccountArgs(applied.accountId))
        if (ownConfiguration.isErr) return federationErr(ownConfiguration.error)
        val statement = ownConfiguration.value
        if (statement.iss != identifier || statement.sub != identifier) {
            return rejected("The account Entity Configuration is not issued for $identifier")
        }
        val unpublished = applied.authorityHints.filterNot { it in statement.authorityHints.orEmpty() }
        if (unpublished.isNotEmpty()) return rejected("Not Immediate Superiors of this Entity: ${unpublished.joinToString()}")
        for (hint in applied.authorityHints) {
            val path = chains.resolve(identifier, selectedAnchors, startingAuthorityHints = listOf(hint))
            if (path.isErr) return rejected("authority_hints entry $hint does not lead to Trust Anchor $selectedTrustAnchor")
        }
        val clientChain = if (applied.includeTrustChains) {
            val chain = chains.resolve(identifier, selectedAnchors, startingAuthorityHints = applied.authorityHints)
            if (chain.isErr) return federationErr(chain.error)
            chain.value.chain
        } else {
            null
        }

        val payload = JsonObject(
            Json.encodeToJsonElement(EntityConfigurationStatement.serializer(), statement).jsonObject +
                mapOf(
                    "iat" to JsonPrimitive(getCurrentEpochTimeSeconds()),
                    "exp" to JsonPrimitive(statement.exp.toLong()),
                    "aud" to JsonPrimitive(provider),
                    "authority_hints" to applied.authorityHints.toJsonArray(),
                    "metadata" to applied.metadata,
                )
        )
        val key = resolveSigningKey.execute(ResolveAccountSigningKeyArgs(applied.accountId, identifier))
        if (key.isErr) return federationErr(key.error)
        val signing = key.value
        val header = JwtHeader(
            alg = signing.alg,
            kid = signing.kid,
            typ = "entity-statement+jwt",
            trustChain = clientChain,
            peerTrustChain = clientChain?.let { providerChain.value.chain },
        )
        val signed = jwtService.signPayload<JsonObject>(payload, header, signing.kid, signing.kmsKeyRef, signing.kms)
        if (signed.isErr) return federationErr(signed.error)

        return IdkResult.ok(
            ExplicitRegistrationRequest(
                clientEntityIdentifier = identifier,
                providerEntityIdentifier = provider,
                profile = profile,
                registrationEndpoint = endpoint,
                contentType = FederationRegistration.ENTITY_STATEMENT_CONTENT_TYPE,
                body = signed.value,
                authorityHints = applied.authorityHints,
                entityTypes = applied.metadata.keys.toList(),
                providerTrustChain = providerChain.value.chain,
                providerTrustAnchor = selectedTrustAnchor,
                clientTrustChain = clientChain,
            )
        )
    }
}
