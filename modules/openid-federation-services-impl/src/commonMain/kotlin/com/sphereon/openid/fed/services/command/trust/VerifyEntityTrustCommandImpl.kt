package com.sphereon.openid.fed.services.command.trust

import com.sphereon.openid.fed.client.command.trustChain.TrustAnchorKeyResolver
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.binary.typeToken
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.core.api.service.TypedServiceCommandAdapter
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommand
import com.sphereon.openid.fed.client.helpers.getCurrentEpochTimeSeconds
import com.sphereon.openid.fed.client.mapper.decodeJWTComponents
import com.sphereon.openid.fed.client.command.trustChain.EntityStatementValidation
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidTrustAnchorError
import com.sphereon.openid.fed.core.error.TrustChainValidationFailedError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.services.command.registration.RegistrationTrustChains
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<VerifyEntityTrustCommand>())
class VerifyEntityTrustCommandImpl(
    execution: SessionExecution,
    resolveTrustChain: ResolveTrustChainCommand,
    verifyTrustChain: VerifyTrustChainCommand,
    private val getEntityConfiguration: GetEntityConfigurationCommand,
    trustAnchorKeys: TrustAnchorKeyResolver,
    private val verifyTrustMark: VerifyTrustMarkCommand,
) : TypedServiceCommandAdapter<VerifyEntityTrustArgs, VerifiedEntityTrust, FederationError>(
    commandId = VerifyEntityTrustCommand.COMMAND_ID,
    execution = execution,
    inputTypeToken = typeToken<VerifyEntityTrustArgs>(),
    outputTypeToken = typeToken<VerifiedEntityTrust>(),
), VerifyEntityTrustCommand {

    private val chains = RegistrationTrustChains(resolveTrustChain, verifyTrustChain, trustAnchorKeys)

    override suspend fun doExecute(
        args: VerifyEntityTrustArgs,
        applyDuring: (VerifyEntityTrustArgs) -> VerifyEntityTrustArgs,
    ): IdkResult<VerifiedEntityTrust, FederationError> {
        val applied = applyDuring(args)
        val subject = applied.entityIdentifier
        val chain = chains.resolve(subject, listOf(applied.trustAnchor), startingAuthorityHints = applied.viaSuperior?.let(::listOf))
        if (chain.isErr) return federationErr(chain.error)
        val verified = chain.value
        applied.viaSuperior?.let { superior ->
            if (verified.immediateSuperior != superior) {
                return federationErr(TrustChainValidationFailedError(subject, "The Trust Chain does not run through $superior"))
            }
        }
        var validUntil = verified.validUntilEpochSeconds

        val required = applied.requiredTrustMarkTypes.distinct()
        if (required.isNotEmpty()) {
            val anchorId = applied.trustAnchor.entityIdentifier
            val anchorConfiguration = getEntityConfiguration.getEntityConfiguration(anchorId)
            if (anchorConfiguration.isErr) {
                return federationErr(InvalidTrustAnchorError(anchorId, "Entity Configuration unavailable: ${anchorConfiguration.error.message.defaultMessage}"))
            }
            val marks = (verified.payloads[0]["trust_marks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val now = getCurrentEpochTimeSeconds()
            for (type in required) {
                val mark = marks.firstOrNull { (it["trust_mark_type"] as? JsonPrimitive)?.content == type }
                    ?.let { (it["trust_mark"] as? JsonPrimitive)?.content }
                    ?: return federationErr(TrustChainValidationFailedError(subject, "The Entity holds no Trust Mark of type $type"))
                val checked = verifyTrustMark.verifyTrustMark(mark, anchorConfiguration.value, now, subject)
                if (checked.isErr) return federationErr(checked.error)
                if (!checked.value.isValid) {
                    return federationErr(TrustChainValidationFailedError(subject, "Trust Mark $type is not valid: ${checked.value.errorMessage ?: "rejected"}"))
                }
                val markExpiry = try {
                    decodeJWTComponents(mark).payload["exp"]?.let(EntityStatementValidation::conservativeExpiryEpochSeconds)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (markExpiry != null) validUntil = minOf(validUntil, markExpiry)
            }
        }

        return IdkResult.ok(
            VerifiedEntityTrust(
                entityIdentifier = subject,
                trustAnchor = verified.trustAnchor,
                trustChain = verified.chain,
                // Subordinate Statements name each superior as issuer; the anchor's own configuration adds no hop.
                chainPath = listOf(subject) + verified.payloads.drop(1)
                    .filter { (it["iss"] as? JsonPrimitive)?.content != (it["sub"] as? JsonPrimitive)?.content }
                    .mapNotNull { (it["iss"] as? JsonPrimitive)?.content },
                verifiedTrustMarkTypes = required,
                validUntilEpochSeconds = validUntil,
            )
        )
    }
}

@ContributesTo(SessionScope::class)
interface TrustCommandDescriptors {
    @Provides @IntoSet
    fun verifyEntityTrust(cmd: Lazy<VerifyEntityTrustCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(VerifyEntityTrustCommand.COMMAND_ID) { cmd.value }
}
