package com.sphereon.oid.fed.services.mappers

import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatementDTO

fun EntityConfigurationStatement.toEntityConfigurationStatementDTO(): EntityConfigurationStatementDTO {
    return EntityConfigurationStatementDTO(
        iss = this.iss,
        sub = this.sub,
        iat = this.iat,
        exp = this.exp,
        jwks = this.jwks,
        metadata = this.metadata,
        authorityHints = this.authorityHints,
        crit = this.crit,
        trustMarkIssuers = this.trustMarkIssuers,
        trustMarks = this.trustMarks
    )
}
