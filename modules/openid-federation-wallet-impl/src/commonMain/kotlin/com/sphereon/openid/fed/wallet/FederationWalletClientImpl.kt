package com.sphereon.openid.fed.wallet

import com.sphereon.openid.fed.core.error.FederationResult
import com.sphereon.openid.fed.wallet.command.*
import com.sphereon.di.session.SessionScope
import kotlinx.serialization.json.JsonObject
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = FederationWalletClient::class)
class FederationWalletClientImpl(
    private val evaluateEntityTrustCommand: EvaluateEntityTrustCommand,
    private val validateEndpointConstraintsCommand: ValidateEndpointConstraintsCommand,
    private val verifyWalletAttestationCommand: VerifyWalletAttestationCommand,
    private val resolveDcqlTrustedAuthoritiesCommand: ResolveDcqlTrustedAuthoritiesCommand,
    private val applyMetadataPolicyCommand: ApplyMetadataPolicyCommand,
    private val verifyCredentialIssuerCommand: VerifyCredentialIssuerCommand,
    private val validateFederationEntityMetadataCommand: ValidateFederationEntityMetadataCommand
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
        currentTime: Long?
    ): FederationResult<FederationEntityMetadataValidationResult> =
        validateFederationEntityMetadataCommand.validateFederationEntityMetadata(entityIdentifier, trustAnchors, entityType, currentTime)
}
