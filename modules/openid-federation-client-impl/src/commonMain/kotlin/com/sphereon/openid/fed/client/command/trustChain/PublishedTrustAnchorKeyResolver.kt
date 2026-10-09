package com.sphereon.openid.fed.client.command.trustChain

import com.sphereon.core.api.IdkResult
import com.sphereon.di.session.SessionScope
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidTrustAnchorError
import com.sphereon.openid.fed.openapi.models.Jwk
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding

@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<TrustAnchorKeyResolver>())
class PublishedTrustAnchorKeyResolver(
    private val getEntityConfiguration: GetEntityConfigurationCommand,
) : TrustAnchorKeyResolver {
    override suspend fun publishedKeys(trustAnchor: String, currentTimeSeconds: Long): IdkResult<List<Jwk>, FederationError> {
        if (!trustAnchor.startsWith("https://")) {
            return IdkResult.err(InvalidTrustAnchorError(trustAnchor, "A Trust Anchor is referenced by its https Entity Identifier"))
        }
        val published = getEntityConfiguration.getEntityConfiguration(trustAnchor)
        if (published.isErr) {
            return IdkResult.err(InvalidTrustAnchorError(trustAnchor, "Entity Configuration unavailable: ${published.error.message.defaultMessage}"))
        }
        val statement = published.value
        if (statement.iss != trustAnchor || statement.sub != trustAnchor) {
            return IdkResult.err(InvalidTrustAnchorError(trustAnchor, "The published Entity Configuration is not issued by the Trust Anchor"))
        }
        if (statement.exp.toLong() <= currentTimeSeconds) {
            return IdkResult.err(InvalidTrustAnchorError(trustAnchor, "The published Entity Configuration has expired"))
        }
        val keys = statement.jwks.propertyKeys.orEmpty()
        if (keys.isEmpty()) return IdkResult.err(InvalidTrustAnchorError(trustAnchor, "The published Entity Configuration has no keys"))
        return IdkResult.ok(keys)
    }
}
