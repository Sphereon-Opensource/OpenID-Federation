package com.sphereon.openid.fed.client.identifier

import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.error.IdkErrorType
import com.sphereon.crypto.resolution.extern.ExternalIdentifierOIDFEntityIdOpts
import com.sphereon.crypto.resolution.extern.ExternalIdentifierOptsOrResult
import com.sphereon.crypto.resolution.extern.ExternalIdentifierResult
import com.sphereon.crypto.resolution.extern.ExternalIdentifierService

/**
 * Service interface for resolving OIDF Entity IDs to signing keys through federation trust chain resolution.
 *
 * This service plugs into the IDK's multi-resolution system so that when any IDK consumer
 * (e.g. verifyJws) encounters an OIDF entity identifier, the federation client automatically
 * resolves it to keys via trust chain resolution.
 */
interface OidfEntityIdExternalIdentifierResolutionService : ExternalIdentifierService {

    override suspend fun resolve(
        opts: ExternalIdentifierOptsOrResult
    ): IdkResult<ExternalIdentifierResult.OIDFEntityId, IdkErrorType>
}
