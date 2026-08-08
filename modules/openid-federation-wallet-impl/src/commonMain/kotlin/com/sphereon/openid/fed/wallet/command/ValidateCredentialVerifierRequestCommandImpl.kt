package com.sphereon.openid.fed.wallet.command

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.ExecutionScopedCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.core.error.EndpointConstraintViolationError
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.logging.federationLogger
import com.sphereon.openid.fed.wallet.policy.CredentialVerifierPolicy
import com.sphereon.openid.fed.wallet.policy.EndpointConstraintValidator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<ValidateCredentialVerifierRequestCommand>())
class ValidateCredentialVerifierRequestCommandImpl(
    execution: SessionExecution
) : ExecutionScopedCommandAdapter<ValidateCredentialVerifierRequestArgs, CredentialVerifierRequestValidationResult, FederationError>(
    id = ValidateCredentialVerifierRequestCommand.COMMAND_ID,
    execution = execution
), ValidateCredentialVerifierRequestCommand {

    private val logger = execution.federationLogger("ValidateCredentialVerifierRequestCommand")

    override suspend fun validateCredentialVerifierRequest(
        entityMetadata: JsonObject,
        requestUri: String?,
        responseUri: String?,
        redirectUri: String?,
        clientMetadata: JsonObject?,
        requestDcqlQuery: JsonElement?
    ): IdkResult<CredentialVerifierRequestValidationResult, FederationError> {
        return execute(
            ValidateCredentialVerifierRequestArgs(
                entityMetadata, requestUri, responseUri, redirectUri, clientMetadata, requestDcqlQuery
            )
        )
    }

    override suspend fun doExecute(
        args: ValidateCredentialVerifierRequestArgs,
        applyDuring: (ValidateCredentialVerifierRequestArgs) -> ValidateCredentialVerifierRequestArgs
    ): IdkResult<CredentialVerifierRequestValidationResult, FederationError> {
        val (entityMetadata, requestUri, responseUri, redirectUri, clientMetadata, requestDcqlQuery) =
            applyDuring(args)

        val verifierMeta = CredentialVerifierPolicy.verifierMetadata(entityMetadata) ?: entityMetadata

        // 1. Endpoints
        val endpointValidations = EndpointConstraintValidator.validate(
            metadata = verifierMeta,
            requestUri = requestUri,
            responseUri = responseUri,
            redirectUri = redirectUri
        )
        for (v in endpointValidations) {
            if (!v.valid) {
                return IdkResult.err(
                    EndpointConstraintViolationError(
                        entityId = "unknown",
                        endpointType = v.endpointType,
                        actualValue = v.value,
                        reason = "not pre-registered. Registered: ${v.registeredValues.joinToString(", ")}"
                    )
                )
            }
        }

        // 2. JWKS preference
        val jwksResolution = CredentialVerifierPolicy.resolveJwks(verifierMeta, clientMetadata)
        if (jwksResolution.jwks == null) {
            logger.warn("No jwks available for credential verifier (federation or client_metadata)")
        }

        // 3. DCQL constraints
        val dcql = CredentialVerifierPolicy.validateDcqlQuery(requestDcqlQuery, verifierMeta)
        if (!dcql.valid) {
            return IdkResult.err(
                InvalidRequestError(dcql.reason ?: "dcql_query rejected by federation policy")
            )
        }

        return IdkResult.ok(
            CredentialVerifierRequestValidationResult(
                valid = true,
                endpointChecks = endpointValidations.associate { it.endpointType to it.valid },
                jwksFromFederation = jwksResolution.fromFederation,
                jwksPresent = jwksResolution.jwks != null,
                dcqlValid = true,
                detail = jwksResolution.detail
            )
        )
    }
}
