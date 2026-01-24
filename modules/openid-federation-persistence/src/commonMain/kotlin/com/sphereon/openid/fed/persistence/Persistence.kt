package com.sphereon.openid.fed.persistence

import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AuthorityHintQueries
import com.sphereon.openid.fed.persistence.models.CritQueries
import com.sphereon.openid.fed.persistence.models.EntityConfigurationStatementQueries
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.persistence.models.LogQueries
import com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.openid.fed.persistence.models.MetadataQueries
import com.sphereon.openid.fed.persistence.models.ReceivedTrustMarkQueries
import com.sphereon.openid.fed.persistence.models.SubordinateJwkQueries
import com.sphereon.openid.fed.persistence.models.SubordinateMetadataQueries
import com.sphereon.openid.fed.persistence.models.SubordinateQueries
import com.sphereon.openid.fed.persistence.models.SubordinateStatementQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuerQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkTypeQueries

/**
 * Exposed object representing the persistence layer of the application, providing access to various
 * query interfaces used for database interactions. These interfaces offer functionalities to
 * interact with specific entities or sets of data in the database.
 *
 * This object includes query interfaces like `EntityConfigurationStatementQueries`,
 * `AccountQueries`, `JwkQueries`, and others, each serving a specialized role in managing and
 * querying their respective data models. Designed for use in environments where platform-specific
 * implementations can be provided.
 */
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
    val metadataPolicyQueries: MetadataPolicyQueries
}
