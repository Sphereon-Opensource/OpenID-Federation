package com.sphereon.openid.fed.wallet

import com.sphereon.openid.fed.client.helpers.OfflineTrustChainPolicy
import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.wallet.command.*
import com.sphereon.di.session.SessionScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FederationWalletClient>())
class FederationWalletClientImpl(
    private val evaluateEntityTrustCommand: EvaluateEntityTrustCommand,
    private val validateEndpointConstraintsCommand: ValidateEndpointConstraintsCommand,
    private val validateCredentialVerifierRequestCommand: ValidateCredentialVerifierRequestCommand,
    private val verifyOfflineTrustChainCommand: VerifyOfflineTrustChainCommand,
    private val verifyWalletAttestationCommand: VerifyWalletAttestationCommand,
    private val resolveDcqlTrustedAuthoritiesCommand: ResolveDcqlTrustedAuthoritiesCommand,
    private val applyMetadataPolicyCommand: ApplyMetadataPolicyCommand,
    private val verifyCredentialIssuerCommand: VerifyCredentialIssuerCommand,
    private val validateFederationEntityMetadataCommand: ValidateFederationEntityMetadataCommand,
    private val checkWalletProviderNonRevocationCommand: CheckWalletProviderNonRevocationCommand,
    private val discoverCredentialIssuersCommand: DiscoverCredentialIssuersCommand,
) : FederationWalletClient {

    override suspend fun evaluateEntityTrust(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        entityTypes: Array<String>?,
        requiredTrustMarks: Array<String>?,
        currentTime: Long?
    ): FederationResult<EntityTrustResult> =
        evaluateEntityTrustCommand.evaluateEntityTrust(entityIdentifier, trustAnchors, entityTypes, requiredTrustMarks, currentTime)

    override suspend fun validateEndpointConstraints(
        entityMetadata: JsonObject,
        requestUri: String?,
        responseUri: String?,
        redirectUri: String?,
        entityType: String
    ): FederationResult<EndpointConstraintsResult> =
        validateEndpointConstraintsCommand.validateEndpointConstraints(entityMetadata, requestUri, responseUri, redirectUri, entityType)

    override suspend fun validateCredentialVerifierRequest(
        entityMetadata: JsonObject,
        requestUri: String?,
        responseUri: String?,
        redirectUri: String?,
        clientMetadata: JsonObject?,
        requestDcqlQuery: JsonElement?
    ): FederationResult<CredentialVerifierRequestValidationResult> =
        validateCredentialVerifierRequestCommand.validateCredentialVerifierRequest(
            entityMetadata, requestUri, responseUri, redirectUri, clientMetadata, requestDcqlQuery
        )

    override suspend fun verifyOfflineTrustChain(
        trustChain: Array<String>,
        trustAnchor: String,
        trustAnchorPublicKeys: List<Jwk>,
        currentTime: Long?,
        policy: OfflineTrustChainPolicy?,
    ): FederationResult<OfflineTrustChainResult> =
        verifyOfflineTrustChainCommand.verifyOfflineTrustChain(
            trustChain, trustAnchor, trustAnchorPublicKeys, currentTime, policy
        )

    override suspend fun verifyWalletAttestation(
        walletAttestationJwt: String,
        trustAnchors: Array<String>,
        currentTime: Long?
    ): FederationResult<WalletAttestationResult> =
        verifyWalletAttestationCommand.verifyWalletAttestation(walletAttestationJwt, trustAnchors, currentTime)

    override suspend fun resolveDcqlTrustedAuthorities(
        credentialIssuerIdentifier: String,
        trustedAuthorities: List<String>,
        currentTime: Long?
    ): FederationResult<DcqlTrustResult> =
        resolveDcqlTrustedAuthoritiesCommand.resolveDcqlTrustedAuthorities(credentialIssuerIdentifier, trustedAuthorities, currentTime)

    override suspend fun applyMetadataPolicy(
        trustChain: Array<String>,
        entityType: String?
    ): FederationResult<EffectiveMetadataResult> =
        applyMetadataPolicyCommand.applyMetadataPolicy(trustChain, entityType)

    override suspend fun verifyCredentialIssuer(
        credentialJwt: String,
        trustAnchors: Array<String>,
        currentTime: Long?
    ): FederationResult<CredentialIssuerResult> =
        verifyCredentialIssuerCommand.verifyCredentialIssuer(credentialJwt, trustAnchors, currentTime)

    override suspend fun validateFederationEntityMetadata(
        entityIdentifier: String,
        trustAnchors: Array<String>,
        entityType: String?,
        currentTime: Long?,
        profileMode: MetadataProfileMode,
    ): FederationResult<FederationEntityMetadataValidationResult> =
        validateFederationEntityMetadataCommand.validateFederationEntityMetadata(
            entityIdentifier, trustAnchors, entityType, currentTime, profileMode
        )

    override suspend fun checkWalletProviderNonRevocation(
        walletProviderEntityId: String,
        trustAnchors: Array<String>,
        requiredTrustMarks: Array<String>?,
        currentTime: Long?,
    ): FederationResult<WalletProviderNonRevocationResult> =
        checkWalletProviderNonRevocationCommand.checkWalletProviderNonRevocation(
            walletProviderEntityId, trustAnchors, requiredTrustMarks, currentTime
        )

    override suspend fun discoverCredentialIssuers(
        trustAnchors: Array<String>,
        startEntityId: String?,
        recursive: Boolean,
        maxDepth: Int,
        verifyTrust: Boolean,
        maxDepthTrustChain: Int,
    ): FederationResult<DiscoverCredentialIssuersResult> =
        discoverCredentialIssuersCommand.discoverCredentialIssuers(
            trustAnchors, startEntityId, recursive, maxDepth, verifyTrust, maxDepthTrustChain
        )
}
