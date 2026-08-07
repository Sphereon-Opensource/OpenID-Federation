package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.core.error.FederationError

import com.sphereon.core.api.service.ServiceCommand

import com.sphereon.openid.fed.openapi.models.TenantJwk

data class GetAssertedKeysArgs(
    val tenantId: String,
    val includeRevoked: Boolean = false,
    val kmsKeyRef: String? = null,
    val kid: String? = null
)

interface GetAssertedKeysCommand : ServiceCommand<GetAssertedKeysArgs, Array<TenantJwk>, FederationError> {
    companion object {
        const val COMMAND_ID = "fed.jwk.get-asserted-keys"
    }
}
