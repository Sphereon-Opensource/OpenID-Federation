package com.sphereon.openid.fed.services.command.entityConfiguration

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.query.QueryParamUtils
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.core.KeyInfo
import com.sphereon.crypto.core.generic.KeyOperations
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.jose.JwaAlgorithm
import com.sphereon.crypto.core.jose.hasWellFormedPublicJwkMaterial
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.crypto.core.sign.keyCompatibilityFailure
import com.sphereon.di.session.SessionScope
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.SelectedSigningKeyConflictError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.services.AccountRepository
import com.sphereon.openid.fed.services.command.jwk.FindAccountSigningKeySelectionArgs
import com.sphereon.openid.fed.services.command.jwk.FindAccountSigningKeySelectionCommand
import com.sphereon.openid.fed.services.signPayload
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi

/**
 * Signs one already-prepared, self-issued Entity Configuration using an explicit active account
 * and selected persisted signing row. This does not persist or publish the resulting JWT.
*/
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<SignPreparedEntityConfigurationCommand>())
class SignPreparedEntityConfigurationCommandImpl internal constructor(
    execution: SessionExecution,
    private val accountRepository: AccountRepository,
    private val jwtService: JwtService,
    private val jwkQueries: JwkQueries,
    private val findAccountSigningKeySelection: FindAccountSigningKeySelectionCommand,
) : TypedServiceCommandAdapter<SignPreparedEntityConfigurationArgs, String, FederationError>(
    commandId = SignPreparedEntityConfigurationCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<SignPreparedEntityConfigurationArgs>(),
    outputTypeToken = typeToken<String>(),
), SignPreparedEntityConfigurationCommand {
    @Inject
    constructor(
        execution: SessionExecution,
        accountRepository: AccountRepository,
        jwtService: JwtService,
        findAccountSigningKeySelection: FindAccountSigningKeySelectionCommand,
    ) : this(
        execution, accountRepository, jwtService, Persistence.jwkQueries,
        findAccountSigningKeySelection,
    )

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun doExecute(
        args: SignPreparedEntityConfigurationArgs,
        applyDuring: (SignPreparedEntityConfigurationArgs) -> SignPreparedEntityConfigurationArgs,
    ): IdkResult<String, FederationError> {
        // JsonObject may wrap caller-owned nested maps/lists. Copy the sole payload before any
        // account/key lookup suspends, then validate and sign only this copy.
        val applied = applyDuring(args)
        val statement = try {
            Json.decodeFromString(
                JsonObject.serializer(),
                Json.encodeToString(JsonObject.serializer(), applied.statement),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return federationErr(InvalidRequestError("Prepared statement cannot be serialized as JSON", e))
        }
        fun invalid(reason: String): IdkResult<String, FederationError> =
            federationErr(InvalidRequestError(reason))

        try {
            val accountResult = accountRepository.findById(applied.accountId)
            if (accountResult.isErr) return federationErr(accountResult.error)
            val account = accountResult.value
                ?: return federationErr(AccountNotFoundError(applied.accountId))
            val identifier = account.identifier
            if (account.id != applied.accountId || identifier.isNullOrBlank()) {
                return invalid("Active account identity does not match the selected account")
            }
            fun claimString(name: String): String? =
                (statement[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (claimString("iss") != identifier || claimString("sub") != identifier) {
                return invalid("Prepared Entity Configuration iss and sub must exactly match the active account")
            }

            if (applied.expectedSelectionRevision < 0L) {
                return invalid("Expected selected signing-key revision must not be negative")
            }
            val selectedKeyId = QueryParamUtils.parseUuid(applied.selectedKeyId)?.toString()
                ?: return invalid("Selected key ID must be a persisted UUID")
            val selected = jwkQueries.findById(selectedKeyId).executeAsOneOrNull()
                ?: return invalid("Selected signing key does not exist")
            if (selected.account_id != account.id || selected.revoked_at != null) {
                return invalid("Selected signing key is not active for this account")
            }
            val kid = selected.kid?.takeIf { it.isNotBlank() }
                ?: return invalid("Selected signing key has no kid")
            val alg = selected.alg?.takeIf { it.isNotBlank() }
                ?: return invalid("Selected signing key has no algorithm")
            if (selected.kms.isBlank() || selected.kms_key_ref.isBlank()) {
                return invalid("Selected signing key has no explicit KMS route")
            }

            val keys = ((statement["jwks"] as? JsonObject)?.get("keys") as? JsonArray)
                ?: return invalid("Prepared Entity Configuration must contain a JWKS keys array")
            if (keys.isEmpty()) return invalid("Prepared Entity Configuration has no federation keys")
            val activeRows = jwkQueries.findByAccountId(account.id).executeAsList()
            val seenKids = mutableSetOf<String>()
            var selectedPublicKey: JsonObject? = null
            for (entry in keys) {
                val publicKey = entry as? JsonObject
                    ?: return invalid("Every advertised federation key must be a JWK object")
                val advertisedKid = (publicKey["kid"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    ?: return invalid("Every advertised federation key must have a string kid")
                if (!seenKids.add(advertisedKid)) return invalid("Duplicate advertised federation kid")
                if (!hasWellFormedPublicJwkMaterial(publicKey)) {
                    return invalid("Advertised federation JWK is not well-formed public material")
                }
                val matchingRows = activeRows.filter { it.kid == advertisedKid }
                if (matchingRows.size != 1 || matchingRows.single().account_id != account.id) {
                    return invalid("Advertised federation JWK has no unique active owned row")
                }
                val row = matchingRows.single()
                val storedPublicKey = runCatching { Json.parseToJsonElement(row.key) as? JsonObject }.getOrNull()
                    ?: return invalid("Persisted federation JWK is not a JSON object")
                if (publicKey != storedPublicKey) {
                    return invalid("Advertised federation JWK differs from its persisted public material")
                }
                if (row.id == selected.id) selectedPublicKey = publicKey
            }
            val signingPublicKey = selectedPublicKey
                ?: return invalid("Selected signing key is not advertised in the prepared JWKS")
            val typedPublicKey = runCatching {
                cryptoJsonSerializer.decodeFromString(CryptoJwk.serializer(), signingPublicKey.toString())
            }.getOrNull() ?: return invalid("Selected public JWK cannot be decoded for signing policy")
            val keyInfo = KeyInfo(key = typedPublicKey)
            val signatureAlgorithm = SignatureAlgorithm.tryFromJoseForKey(
                JwaAlgorithm.fromValue(alg), keyInfo,
            )
            if (signatureAlgorithm.isErr ||
                keyInfo.keyCompatibilityFailure(signatureAlgorithm.value, KeyOperations.VERIFY) != null
            ) {
                return invalid("Selected public JWK is incompatible with its persisted signing algorithm")
            }

            val header = JwtHeader(
                alg = alg,
                kid = kid,
                typ = EntityStatementValidation.ENTITY_STATEMENT_TYP,
            )
            val structural = runCatching {
                EntityStatementValidation.validateStructure(
                    statement = Jwt(header = header, payload = statement, signature = ""),
                    currentTimeSeconds = getCurrentEpochTimeSeconds(),
                    position = 0,
                )
            }.getOrNull() ?: return invalid("Prepared Entity Configuration has invalid structure")
            if (!structural.ok) return invalid(structural.reason ?: "Invalid Entity Configuration")

            // Read the authoritative selected UUID and revision after all existing ownership,
            // advertised-key, and structure checks, immediately before the KMS boundary.
            val selectionResult = findAccountSigningKeySelection.execute(
                FindAccountSigningKeySelectionArgs(account.id, identifier),
            )
            if (selectionResult.isErr) return federationErr(selectionResult.error)
            val currentSelectedKeyId = selectionResult.value.selectedKeyId
                ?.let { QueryParamUtils.parseUuid(it)?.toString() }
            if (currentSelectedKeyId != selectedKeyId ||
                selectionResult.value.revision != applied.expectedSelectionRevision
            ) {
                return federationErr(SelectedSigningKeyConflictError(account.id))
            }

            return jwtService.signPayload(
                payload = statement,
                header = header,
                kid = kid,
                kmsKeyRef = selected.kms_key_ref,
                kmsProviderId = selected.kms,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return federationErr(ServerError("Failed to sign prepared Entity Configuration", e.message, e))
        }
    }
}
