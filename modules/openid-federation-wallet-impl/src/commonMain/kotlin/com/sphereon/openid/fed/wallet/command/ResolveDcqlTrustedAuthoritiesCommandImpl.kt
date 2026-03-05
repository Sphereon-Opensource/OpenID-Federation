package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.DcqlTrustAuthorityNotFoundError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.logging.federationLogger
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, boundType = ResolveDcqlTrustedAuthoritiesCommand::class)
class ResolveDcqlTrustedAuthoritiesCommandImpl(
    execution: SessionExecution,
    private val evaluateEntityTrustCommand: EvaluateEntityTrustCommand
) : ExecutionScopedCommandAdapter<ResolveDcqlTrustedAuthoritiesArgs, DcqlTrustResult, FederationError>(
    id = ResolveDcqlTrustedAuthoritiesCommand.COMMAND_ID,
    execution = execution
), ResolveDcqlTrustedAuthoritiesCommand {

    private val logger = execution.federationLogger("ResolveDcqlTrustedAuthoritiesCommand")

    override suspend fun resolveDcqlTrustedAuthorities(
        credentialIssuerIdentifier: String,
        trustedAuthorities: List<String>,
        currentTime: Long?
    ): IdkResult<DcqlTrustResult, FederationError> {
        return execute(ResolveDcqlTrustedAuthoritiesArgs(credentialIssuerIdentifier, trustedAuthorities, currentTime))
    }

    override suspend fun doExecute(
        args: ResolveDcqlTrustedAuthoritiesArgs,
        applyDuring: (ResolveDcqlTrustedAuthoritiesArgs) -> ResolveDcqlTrustedAuthoritiesArgs
    ): IdkResult<DcqlTrustResult, FederationError> {
        val (credentialIssuerIdentifier, trustedAuthorities, currentTime) = applyDuring(args)

        logger.info("Resolving DCQL trusted authorities for credential issuer: $credentialIssuerIdentifier")

        if (trustedAuthorities.isEmpty()) {
            return IdkResult.err(DcqlTrustAuthorityNotFoundError(
                credentialIssuerId = credentialIssuerIdentifier,
                attemptedAuthorities = emptyList()
            ))
        }

        // Try each trusted authority until one succeeds
        for (authority in trustedAuthorities) {
            logger.debug("Trying trust anchor: $authority for issuer: $credentialIssuerIdentifier")

            val trustResult = evaluateEntityTrustCommand.evaluateEntityTrust(
                entityIdentifier = credentialIssuerIdentifier,
                trustAnchors = arrayOf(authority),
                entityTypes = arrayOf("openid_credential_issuer"),
                currentTime = currentTime
            )

            if (trustResult.isOk) {
                logger.info("Credential issuer $credentialIssuerIdentifier trusted via authority: $authority")
                return IdkResult.ok(DcqlTrustResult(
                    trusted = true,
                    matchedAuthority = authority,
                    entityTrustResult = trustResult.value
                ))
            }

            logger.debug("Trust evaluation failed for authority $authority: ${trustResult.error.message.defaultMessage}")
        }

        logger.error("Credential issuer $credentialIssuerIdentifier not trusted by any DCQL authority")
        return IdkResult.err(DcqlTrustAuthorityNotFoundError(
            credentialIssuerId = credentialIssuerIdentifier,
            attemptedAuthorities = trustedAuthorities
        ))
    }
}
