package com.sphereon.oid.fed.persistence

import com.sphereon.oid.fed.persistence.models.*

expect object Persistence {
    val entityConfigurationStatementQueries: EntityConfigurationStatementQueries
    val accountQueries: AccountQueries
    val jwkQueries: JwkQueries
    val subordinateQueries: SubordinateQueries
    val metadataQueries: MetadataQueries
    val authorityHintQueries: AuthorityHintQueries
    val critQueries: CritQueries
    val subordinateStatementQueries: SubordinateStatementQueries
    val subordinateJwkQueries: SubordinateJwkQueries
    val subordinateMetadataQueries: SubordinateMetadataQueries
    val trustMarkTypeQueries: TrustMarkTypeQueries
    val trustMarkIssuerQueries: TrustMarkIssuerQueries
    val trustMarkQueries: TrustMarkQueries
    val receivedTrustMarkQueries: ReceivedTrustMarkQueries
    val logQueries: LogQueries
}
