package com.sphereon.openid.fed.services.command.trustMark

import com.sphereon.core.api.service.ServiceCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.Jwk
import kotlinx.serialization.Serializable

/**
 * The owner a Federation Operator records for one of its Trust Mark types, published under `trust_mark_owners`
 * (OpenID Federation 1.1 section 7.2). [keys] are the owner's Federation Entity Keys that sign its delegations.
 */
@Serializable
data class TrustMarkTypeOwner(
    val owner: String,
    val keys: List<Jwk>,
)

/** Who owns a Trust Mark type of the account and the delegation the account issues it under, if any. */
@Serializable
data class TrustMarkTypeGovernance(
    val trustMarkTypeId: String,
    val trustMarkType: String,
    val owner: TrustMarkTypeOwner? = null,
    /** The Trust Mark delegation JWT the account embeds in every Trust Mark of this type it issues. */
    val delegation: String? = null,
)

data class TrustMarkTypeRefArgs(val tenantId: String, val trustMarkTypeId: String)

interface GetTrustMarkTypeGovernanceCommand : ServiceCommand<TrustMarkTypeRefArgs, TrustMarkTypeGovernance, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.get-type-governance"
    }
}

data class SetTrustMarkTypeOwnerArgs(val tenantId: String, val trustMarkTypeId: String, val owner: String, val keys: List<Jwk>)

/** Records the owner of a Trust Mark type; it replaces an earlier owner of the type. */
interface SetTrustMarkTypeOwnerCommand : ServiceCommand<SetTrustMarkTypeOwnerArgs, TrustMarkTypeGovernance, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.set-type-owner"
    }
}

interface RemoveTrustMarkTypeOwnerCommand : ServiceCommand<TrustMarkTypeRefArgs, TrustMarkTypeGovernance, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.remove-type-owner"
    }
}

data class SetTrustMarkTypeDelegationArgs(val tenantId: String, val trustMarkTypeId: String, val delegation: String?)

/** Sets, or with a null delegation clears, the delegation the account issues a Trust Mark type under. */
interface SetTrustMarkTypeDelegationCommand : ServiceCommand<SetTrustMarkTypeDelegationArgs, TrustMarkTypeGovernance, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.set-type-delegation"
    }
}

/**
 * Signs a Trust Mark delegation JWT (`trust-mark-delegation+jwt`, section 7.2.1) with the account's selected key: the
 * account, as owner of [trustMarkType], lets [subject] issue Trust Marks of that type.
 */
data class CreateTrustMarkDelegationArgs(
    val tenantId: String,
    val trustMarkType: String,
    val subject: String,
    val issuedAt: Long,
    val expiresAt: Long?,
    val ref: String? = null,
)

interface CreateTrustMarkDelegationCommand : ServiceCommand<CreateTrustMarkDelegationArgs, String, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.trust-mark.create-delegation"
    }
}
