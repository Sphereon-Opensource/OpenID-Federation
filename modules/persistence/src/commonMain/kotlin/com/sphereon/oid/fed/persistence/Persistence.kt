package com.sphereon.oid.fed.persistence

import com.sphereon.oid.fed.persistence.models.*

expect object Persistence {
    val entityConfigurationStatementQueries: EntityConfigurationStatementQueries
    val accountQueries: AccountQueries
    val keyQueries: KeyQueries
    val subordinateQueries: SubordinateQueries
    val entityConfigurationMetadataQueries: EntityConfigurationMetadataQueries
}
