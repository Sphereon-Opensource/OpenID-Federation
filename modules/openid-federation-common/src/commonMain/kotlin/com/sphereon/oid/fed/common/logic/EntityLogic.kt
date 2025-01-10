import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatementDTO
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EntityLogic {

    fun getEntityType(entityStatement: EntityConfigurationStatementDTO): EntityType {
        val hasFederationListEndpoint = isFederationListEndpointPresent(entityStatement)
        val hasAuthorityHint = isAuthorityHintPresent(entityStatement)

        return when {
            hasFederationListEndpoint && hasAuthorityHint -> EntityType.INTERMEDIATE
            hasFederationListEndpoint && !hasAuthorityHint -> EntityType.TRUST_ANCHOR
            !hasFederationListEndpoint && hasAuthorityHint -> EntityType.LEAF
            else -> EntityType.UNDEFINED
        }
    }

    private fun isAuthorityHintPresent(entityStatement: EntityConfigurationStatementDTO): Boolean =
        entityStatement.authorityHints?.isNotEmpty() ?: false

    private fun isFederationListEndpointPresent(entityStatement: EntityConfigurationStatementDTO): Boolean {
        val federationEntity = entityStatement.metadata?.get("federation_entity")?.jsonObject
        val federationListEndpoint = federationEntity?.get("federation_list_endpoint")?.jsonPrimitive?.contentOrNull
        return federationListEndpoint?.isNotEmpty() ?: false
    }
}

enum class EntityType {
    LEAF, INTERMEDIATE, TRUST_ANCHOR, UNDEFINED
}
