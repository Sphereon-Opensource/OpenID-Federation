package com.sphereon.openid.fed.services.command.subordinate

import com.sphereon.core.api.IdkResult
import com.sphereon.openid.fed.common.builder.SubordinateStatementObjectBuilder
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.federationErr
import com.sphereon.openid.fed.openapi.models.Constraints
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.wallet.policy.MetadataPolicyOperators
import kotlinx.serialization.json.JsonObject

/** Pure payload construction shared by stored subordinate reads and reviewed-snapshot preparation. */
internal fun buildSubordinateStatementSnapshot(
    issuer: String,
    subject: String,
    keys: List<Jwk>,
    metadata: List<Pair<String, JsonObject>>,
    policies: List<Pair<String, JsonObject>>,
    constraints: Constraints?,
    sourceEndpoint: String,
    lifetimeSeconds: Long?,
    currentTimeSeconds: Double,
): IdkResult<SubordinateStatement, FederationError> {
    val lifetime = lifetimeSeconds
        ?: return federationErr(ServerError("Subordinate statement lifetime is not configured"))
    check(issuer.isNotEmpty()) { "Account identifier is empty" }
    val statement = SubordinateStatementObjectBuilder()
        .iss(issuer).sub(subject).iat(currentTimeSeconds).exp(currentTimeSeconds + lifetime)
        .sourceEndpoint(sourceEndpoint)
    if (keys.isEmpty()) return federationErr(InvalidRequestError("Subordinate $subject has no public keys to vouch for"))
    keys.forEach(statement::jwks)
    metadata.forEach(statement::metadata)
    for ((entityType, policy) in policies) {
        val checked = MetadataPolicyOperators.mergePolicies(JsonObject(emptyMap()), JsonObject(mapOf(entityType to policy)))
        if (checked is MetadataPolicyOperators.PolicyMergeResult.Error) {
            return federationErr(ServerError("Stored metadata policy for $entityType is invalid", checked.reason))
        }
        statement.metadataPolicy(entityType to policy)
    }
    if (constraints != null) statement.constraints(constraints)
    return IdkResult.ok(statement.build())
}
