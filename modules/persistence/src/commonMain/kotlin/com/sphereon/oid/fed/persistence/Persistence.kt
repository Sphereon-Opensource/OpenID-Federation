package com.sphereon.oid.fed.persistence

import com.sphereon.oid.fed.persistence.models.AccountQueries
import com.sphereon.oid.fed.persistence.models.AuthorityHintQueries
import com.sphereon.oid.fed.persistence.models.EntityConfigurationMetadataQueries
import com.sphereon.oid.fed.persistence.models.EntityConfigurationStatementQueries
import com.sphereon.oid.fed.persistence.models.KeyQueries
import com.sphereon.oid.fed.persistence.models.SubordinateQueries

expect object Persistence {
    val entityConfigurationStatementQueries: EntityConfigurationStatementQueries
    val accountQueries: AccountQueries
    val keyQueries: KeyQueries
    val subordinateQueries: SubordinateQueries
    val entityConfigurationMetadataQueries: EntityConfigurationMetadataQueries
    val authorityHintQueries: AuthorityHintQueries
}
