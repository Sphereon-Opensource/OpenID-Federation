package com.sphereon.openid.fed.core.config

import kotlin.test.Test
import kotlin.test.assertEquals

class FederationClientAuthMembershipPolicyTest {

    @Test
    fun fromConfig_parsesAliases() {
        assertEquals(
            FederationClientAuthMembershipPolicy.ANY_FETCHABLE,
            FederationClientAuthMembershipPolicy.fromConfig("any_fetchable"),
        )
        assertEquals(
            FederationClientAuthMembershipPolicy.ANY_FETCHABLE,
            FederationClientAuthMembershipPolicy.fromConfig("open"),
        )
        assertEquals(
            FederationClientAuthMembershipPolicy.SUBORDINATE_OF_SELF,
            FederationClientAuthMembershipPolicy.fromConfig("subordinate"),
        )
        assertEquals(
            FederationClientAuthMembershipPolicy.TRUST_CHAIN_TO_TA,
            FederationClientAuthMembershipPolicy.fromConfig("trust-chain"),
        )
        assertEquals(
            FederationClientAuthMembershipPolicy.HYBRID,
            FederationClientAuthMembershipPolicy.fromConfig("hybrid"),
        )
        assertEquals(
            FederationClientAuthMembershipPolicy.HYBRID,
            FederationClientAuthMembershipPolicy.fromConfig("unknown-default"),
        )
    }
}
