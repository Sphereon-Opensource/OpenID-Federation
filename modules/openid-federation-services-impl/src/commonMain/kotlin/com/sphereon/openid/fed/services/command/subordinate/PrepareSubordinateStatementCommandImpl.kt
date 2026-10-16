package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.crypto.core.KeyInfo
import com.sphereon.crypto.core.generic.KeyOperations
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.jose.JwaAlgorithm
import com.sphereon.crypto.core.jose.Jwk as CryptoJwk
import com.sphereon.crypto.core.jose.hasWellFormedPublicJwkMaterial
import com.sphereon.crypto.core.json.cryptoJsonSerializer
import com.sphereon.crypto.core.sign.keyCompatibilityFailure
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.jose.jws.JwsCompact
import com.sphereon.crypto.jose.jws.command.VerifyJwsArgs
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.common.builder.FederationEndpointUrls
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import com.sphereon.openid.fed.core.config.OidfConfigBinder
import com.sphereon.openid.fed.core.error.AccountNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.FederationErrorException
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.Jwt
import com.sphereon.openid.fed.openapi.models.JwtHeader
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.persistence.Persistence
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.openid.fed.persistence.models.MetadataQueries
import com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries
import com.sphereon.openid.fed.services.command.metadataPolicy.captureAccountMetadataPolicies
import com.sphereon.openid.fed.services.AccountRepository
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyArgs
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommand
import com.sphereon.openid.fed.services.signPayload
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

/** Read-only preparation. Normal signing may call the managed KMS; no federation rows are written. */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<PrepareSubordinateStatementCommand>())
class PrepareSubordinateStatementCommandImpl internal constructor(
    execution: SessionExecution,
    private val accounts: AccountRepository,
    private val jwtService: JwtService,
    private val resolveSigningKey: ResolveAccountSigningKeyCommand,
    private val config: OidfConfigBinder,
    private val policies: MetadataPolicyQueries,
    private val metadata: MetadataQueries,
    private val keys: JwkQueries,
    private val revisions: AccountMetadataPolicyRevisionQueries,
    private val accountQueries: AccountQueries,
) : TypedServiceCommandAdapter<PrepareSubordinateStatementArgs, PreparedSubordinateStatement, FederationError>(
    commandId = PrepareSubordinateStatementCommand.COMMAND_ID, execution = execution,
    inputTypeToken = typeToken<PrepareSubordinateStatementArgs>(), outputTypeToken = typeToken<PreparedSubordinateStatement>(),
), PrepareSubordinateStatementCommand {
    @Inject
    constructor(execution: SessionExecution, accounts: AccountRepository, jwtService: JwtService,
        resolveSigningKey: ResolveAccountSigningKeyCommand, config: OidfConfigBinder) : this(
        execution, accounts, jwtService, resolveSigningKey, config, Persistence.metadataPolicyQueries,
        Persistence.metadataQueries, Persistence.jwkQueries, Persistence.accountMetadataPolicyRevisionQueries, Persistence.accountQueries)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private fun policySnapshot(accountId: String, issuer: String) =
        captureAccountMetadataPolicies(accountQueries, policies, revisions, accountId, issuer)
    private fun fetchEndpoint(accountId: String, issuer: String): String =
        metadata.findByAccountIdAndKey(accountId, "federation_entity").executeAsList().lastOrNull()
            ?.let { (Json.parseToJsonElement(it.metadata) as? JsonObject)?.get("federation_fetch_endpoint") as? JsonPrimitive }
            ?.takeIf { it.isString }?.content ?: FederationEndpointUrls.fetch(issuer)

    override suspend fun doExecute(args: PrepareSubordinateStatementArgs, applyDuring: (PrepareSubordinateStatementArgs) -> PrepareSubordinateStatementArgs): IdkResult<PreparedSubordinateStatement, FederationError> {
        fun invalid(reason: String): IdkResult<PreparedSubordinateStatement, FederationError> = federationErr(InvalidRequestError(reason))
        return try {
            // Freeze every caller-owned nested collection before any suspending account/key/KMS operation.
            val input = json.decodeFromString(PrepareSubordinateStatementArgs.serializer(),
                json.encodeToString(PrepareSubordinateStatementArgs.serializer(), applyDuring(args)))
            val expectedSelectionRevision = input.expectedSelectionRevision
            if (expectedSelectionRevision != null && expectedSelectionRevision < 0L) return invalid("Invalid expected signing selection revision")
            if (input.expectedMetadataPolicyRevisions?.values?.any { it < 0L } == true) return invalid("Invalid expected metadata policy revision")
            val found = accounts.findById(input.accountId)
            if (found.isErr) return federationErr(found.error)
            val account = found.value ?: return federationErr(AccountNotFoundError(input.accountId))
            val issuer = account.identifier?.takeIf { it.isNotBlank() } ?: return invalid("Superior account has no Entity Identifier")
            if (account.id != input.accountId) return invalid("Superior account ownership mismatch")
            if (issuer == input.subject) return invalid("Subordinate subject must differ from its superior")
            if (input.publicJwks.isEmpty()) return invalid("Reviewed subordinate has no public keys")
            val seen = mutableSetOf<String>()
            for (jwk in input.publicJwks) {
                if (!hasWellFormedPublicJwkMaterial(jwk)) return invalid("Reviewed subordinate JWK is not well-formed public material")
                val kid = (jwk["kid"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    ?: return invalid("Reviewed subordinate JWK needs a kid")
                if (!seen.add(kid)) return invalid("Reviewed subordinate JWK kids must be unique")
            }
            val lifetime = config.getFederationConfig().statementLifetimeSeconds
                ?: return federationErr(ServerError("Subordinate statement lifetime is not configured"))
            val now = System.currentTimeMillis() / 1000
            if (lifetime <= 0L || lifetime > 9_007_199_254_740_991L - now) return invalid("Invalid subordinate statement lifetime")
            val policy = policySnapshot(account.id, issuer)
            if (input.expectedMetadataPolicy != null && input.expectedMetadataPolicy != policy.snapshot.metadataPolicy) return invalid("Account metadata policy changed")
            if (input.expectedMetadataPolicyRevisions != null && input.expectedMetadataPolicyRevisions != policy.snapshot.metadataPolicyRevisions) return invalid("Account metadata policy revision changed")
            val endpoint = fetchEndpoint(account.id, issuer)
            val prepared = buildSubordinateStatementSnapshot(issuer, input.subject,
                input.publicJwks.map { json.decodeFromJsonElement(Jwk.serializer(), it) },
                input.metadata.orEmpty().map { (role, claims) -> role to (claims as? JsonObject
                    ?: return invalid("Reviewed metadata roles must contain objects")) },
                policy.rows, input.constraints, endpoint, lifetime, now.toDouble())
            if (prepared.isErr) return federationErr(prepared.error)
            // Preserve exact reviewed public keys, including legal JWK extensions, in the signed payload.
            val payload = JsonObject(json.encodeToJsonElement(SubordinateStatement.serializer(), prepared.value).jsonObject +
                ("jwks" to buildJsonObject { put("keys", JsonArray(input.publicJwks)) }))
            val resolved = resolveSigningKey.execute(ResolveAccountSigningKeyArgs(account.id, issuer))
            if (resolved.isErr) return federationErr(resolved.error)
            val selection = resolved.value
            if (selection.accountId != account.id || selection.entityIdentifier != issuer) return invalid("Selected signer belongs to another account")
            if (expectedSelectionRevision != null && expectedSelectionRevision != selection.selectionRevision) return invalid("Selected signing key revision changed")
            val row = keys.findActiveById(selection.keyId).executeAsOneOrNull() ?: return invalid("Selected signing key is not active")
            if (row.account_id != account.id || row.kid != selection.kid || row.alg != selection.alg ||
                row.kms != selection.kms || row.kms_key_ref != selection.kmsKeyRef || row.revoked_at != null) return invalid("Selected signing key ownership or route changed")
            val publicKey = Json.parseToJsonElement(row.key).jsonObject
            if (!hasWellFormedPublicJwkMaterial(publicKey)) return invalid("Selected signing key lacks valid public material")
            val typed = cryptoJsonSerializer.decodeFromString(CryptoJwk.serializer(), publicKey.toString())
            val info = KeyInfo(key = typed)
            val algorithm = SignatureAlgorithm.tryFromJoseForKey(JwaAlgorithm.fromValue(selection.alg), info)
            if (algorithm.isErr || info.keyCompatibilityFailure(algorithm.value, KeyOperations.VERIFY) != null) return invalid("Selected public key is incompatible with its signing algorithm")
            val header = JwtHeader(typ = EntityStatementValidation.ENTITY_STATEMENT_TYP, kid = selection.kid, alg = selection.alg)
            val structural = EntityStatementValidation.validateStructure(Jwt(header = header, payload = payload, signature = ""), now, position = 1)
            if (!structural.ok) return invalid(structural.reason ?: "Invalid prepared subordinate statement")
            val signed = jwtService.signPayload(payload, header, selection.kid, selection.kmsKeyRef, selection.kms)
            if (signed.isErr) return federationErr(signed.error)
            val verified = jwtService.verifyJws(VerifyJwsArgs(jws = JwsCompact(signed.value), trustedJwks = buildJsonObject { put("keys", JsonArray(listOf(publicKey))) }))
            if (verified.isErr || !verified.value.isValid) return invalid("Prepared signature does not verify against the selected persisted public key")
            val afterSelection = resolveSigningKey.execute(ResolveAccountSigningKeyArgs(account.id, issuer))
            if (afterSelection.isErr) return federationErr(afterSelection.error)
            if (afterSelection.value != selection || keys.findActiveById(selection.keyId).executeAsOneOrNull() != row) return invalid("Selected signing key changed during preparation")
            if (policySnapshot(account.id, issuer) != policy || fetchEndpoint(account.id, issuer) != endpoint) return invalid("Account policy or source endpoint changed during preparation")
            if (System.currentTimeMillis() / 1000 >= prepared.value.exp.toLong()) return invalid("Prepared statement expired during signing")
            IdkResult.ok(PreparedSubordinateStatement(signed.value, account.id, issuer, input.subject, selection.keyId,
                selection.kid, selection.alg, selection.selectionRevision, policy.snapshot.metadataPolicy, policy.snapshot.metadataPolicyRevisions))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: FederationErrorException) {
            federationErr(rejected.federationError)
        } catch (failure: Exception) {
            federationErr(ServerError("Failed to prepare subordinate statement", failure.message, failure))
        }
    }
}
