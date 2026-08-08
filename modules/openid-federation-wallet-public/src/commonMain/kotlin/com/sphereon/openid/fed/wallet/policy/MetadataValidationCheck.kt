package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.Serializable

/**
 * Result of a single metadata profile validation check (wallet architecture or DIIP).
 */
@Serializable
data class MetadataValidationCheck(
    val check: String,
    val passed: Boolean,
    val detail: String? = null,
    /** Profile that produced this check: [PROFILE_WALLET] or [PROFILE_DIIP]. */
    val profile: String = PROFILE_WALLET,
) {
    companion object {
        const val PROFILE_WALLET = "wallet"
        const val PROFILE_DIIP = "diip"
    }
}
