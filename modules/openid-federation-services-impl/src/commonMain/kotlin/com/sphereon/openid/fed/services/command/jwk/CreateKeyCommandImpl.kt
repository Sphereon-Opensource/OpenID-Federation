package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.asErrorResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.conf.TenantConfigService
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.crypto.core.CoseJoseKeyMappingService
import com.sphereon.crypto.core.KeyVisibility
import com.sphereon.crypto.core.generic.KeyOperations
import com.sphereon.crypto.core.sign.keyCompatibilityFailure
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.TenantJwk
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.services.CreateKeyArgs
import com.sphereon.openid.fed.services.config.resolveEffectiveKmsProviderId
import com.sphereon.openid.fed.services.mappers.toDTO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

/**
 * Implementation of the CreateKeyCommand.
 * Creates a new JSON Web Key (JWK) for an account.
 *
 * When [CreateKeyArgs.providerId] is null/blank, the KMS provider is resolved via
 * [OidfConfigBinder.getEffectiveKmsConfig] for the tenant (session tenant config →
 * `oidf.tenant.<id>.kms.provider` → APP default).
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<CreateKeyCommand>())
class CreateKeyCommandImpl(
    execution: SessionExecution,
    private val keyManagerService: KeyManagerService,
    private val getKeysCommand: GetKeysCommand,
    private val configBinder: OidfConfigBinder,
    private val tenantConfigService: TenantConfigService,
) : TypedServiceCommandAdapter<CreateKeyCommandArgs, TenantJwk, FederationError>(
    commandId = CreateKeyCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<CreateKeyCommandArgs>(),
    outputTypeToken = typeToken<TenantJwk>()
), CreateKeyCommand {

    private val logger = execution.federationLogger("CreateKeyCommand")
    private val jwkQueries = Persistence.jwkQueries

    override suspend fun doExecute(
        args: CreateKeyCommandArgs,
        applyDuring: (CreateKeyCommandArgs) -> CreateKeyCommandArgs
    ): IdkResult<TenantJwk, FederationError> = withContext(Dispatchers.IO) {
        val (tenantId, opts) = applyDuring(args)

        try {
            logger.info("Creating new key for account: ${tenantId}, with options: $opts")
            logger.debug("Found account with ID: ${tenantId}")
            val (_, alias, use, keyOperations, alg) = opts
            val providerId = configBinder.resolveEffectiveKmsProviderId(
                tenantId = tenantId,
                explicitProviderId = opts.providerId,
                sessionTenantConfig = tenantConfigService,
            )
            logger.debug("Using KMS providerId=$providerId for tenant=$tenantId")

            // Check if key with same alias already exists
            val existingKeysResult = getKeysCommand.execute(GetKeysArgs(tenantId, includeRevoked = false))
            if (existingKeysResult.isErr) {
                return@withContext existingKeysResult.error.asErrorResult()
            }
            val existingKeys = existingKeysResult.value

            if (existingKeys.any { alias != null && it.kmsKeyRef == alias }) {
                return@withContext federationErr(ServerError("Key with alias $alias already exists for account ID: ${tenantId}"))
            }

            // A routed generation returns an authoritative reference; its in-process key pair is
            // deliberately transient. Never regenerate or switch providers when a receipt is invalid.
            val generated = keyManagerService.generateKeyResult(providerId, alias, use, keyOperations, alg, KeyVisibility.PUBLIC)
            if (generated.isErr) return@withContext federationErr(ServerError("Failed to create key", generated.error.message.defaultMessage))
            val reference = requireNotNull(generated.value.keyReference) { "Generated managed key reference is missing" }
            require(reference.alias.isNotBlank() && reference.providerId.isNotBlank()) { "Generated managed key coordinates are blank" }
            require(reference.kid == null || reference.kid!!.isNotBlank()) { "Generated key ID is blank" }
            require(alias == null || reference.alias == alias) { "Generated managed key alias differs from the requested alias" }
            require(reference.providerId == providerId) { "Generated managed key provider differs from the selected provider" }
            require(reference.signatureAlgorithm == null || reference.signatureAlgorithm == alg) { "Generated managed key algorithm differs from the requested algorithm" }

            val resolved = keyManagerService.getKeyResult(reference.copy(keyVisibility = KeyVisibility.PUBLIC))
            if (resolved.isErr) return@withContext federationErr(ServerError("Failed to resolve generated public key", resolved.error.message.defaultMessage))
            val keyInfo = requireNotNull(resolved.value.key) { "Generated public key is missing" }
            require(keyInfo.alias == reference.alias && keyInfo.providerId == reference.providerId) { "Resolved public key coordinates differ from the generated reference" }
            val publicInfo = CoseJoseKeyMappingService.toResolvedJwkKeyInfo(keyInfo)
            val publicJwk = publicInfo.key
            require(publicJwk.d == null && publicJwk.p == null && publicJwk.q == null && publicJwk.dP == null &&
                publicJwk.dQ == null && publicJwk.qInv == null && publicJwk.k == null) { "Generated key lookup returned private or symmetric material" }
            require(publicInfo.keyCompatibilityFailure(alg, KeyOperations.VERIFY) == null) { "Generated public key is incompatible with the requested signing algorithm" }
            val kids = listOfNotNull(reference.kid, keyInfo.kid, publicJwk.kid)
            require(kids.all { it.isNotBlank() } && kids.distinct().size <= 1) { "Resolved public key ID differs from the generated reference" }
            val kid = kids.firstOrNull() ?: requireNotNull(publicJwk.getKeyId(true)) { "Generated public key has no key ID" }
            val algorithm = requireNotNull(alg.jose?.value) { "Generated key has no JOSE signing algorithm" }
            val persistedPublicJwk = publicJwk.copy(kid = kid)
            logger.debug("Generated public key with KID: $kid providerId: ${reference.providerId} and alias: ${reference.alias} for account ID: $tenantId")

            val createdKey = jwkQueries.create(
                account_id = tenantId,
                kid = kid,
                kms_key_ref = reference.alias,
                kms = reference.providerId,
                alg = algorithm,
                key = persistedPublicJwk.toJsonString()
            ).executeAsOne()

            logger.info("Successfully created key with KID: $kid for account ID: $tenantId")
            IdkResult.ok(createdKey.toDTO())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.error("Failed to create key for account: ${tenantId} due to: ${e.message}", e)
            federationErr(ServerError("Failed to create key", e.message, e))
        }
    }
}
