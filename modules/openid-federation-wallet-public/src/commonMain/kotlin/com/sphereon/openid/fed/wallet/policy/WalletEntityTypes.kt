package com.sphereon.openid.fed.wallet.policy

/**
 * Entity Type Identifiers for OpenID Federation Wallet Architectures (draft 05 Table 1).
 *
 * | Entity               | Entity Type Identifiers                                                       |
 * |----------------------|-------------------------------------------------------------------------------|
 * | Trust Anchor         | federation_entity                                                             |
 * | Intermediate         | federation_entity                                                             |
 * | Wallet Provider      | federation_entity, openid_wallet_provider                                     |
 * | Authorization Server | federation_entity, oauth_authorization_server                                 |
 * | Credential Issuer    | federation_entity, openid_credential_issuer, oauth_authorization_server (opt) |
 * | Credential Verifier  | federation_entity, openid_credential_verifier                                 |
 */
object WalletEntityTypes {

    const val FEDERATION_ENTITY = "federation_entity"
    const val OPENID_WALLET_PROVIDER = "openid_wallet_provider"
    const val OPENID_CREDENTIAL_ISSUER = "openid_credential_issuer"
    const val OPENID_CREDENTIAL_VERIFIER = "openid_credential_verifier"
    const val OAUTH_AUTHORIZATION_SERVER = "oauth_authorization_server"

    /** All wallet-architecture organizational entity types from Table 1. */
    val ALL: Set<String> = setOf(
        FEDERATION_ENTITY,
        OPENID_WALLET_PROVIDER,
        OPENID_CREDENTIAL_ISSUER,
        OPENID_CREDENTIAL_VERIFIER,
        OAUTH_AUTHORIZATION_SERVER,
    )

    /** Entity types that appear as Leaf protocol roles (not pure federation_entity infra). */
    val LEAF_PROTOCOL_TYPES: Set<String> = setOf(
        OPENID_WALLET_PROVIDER,
        OPENID_CREDENTIAL_ISSUER,
        OPENID_CREDENTIAL_VERIFIER,
        OAUTH_AUTHORIZATION_SERVER,
    )

    fun isKnown(entityType: String): Boolean = entityType in ALL
}
