package com.sphereon.openid.fed.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.persistence.models.AuthorityHintQueries
import com.sphereon.openid.fed.persistence.models.CritQueries
import com.sphereon.openid.fed.persistence.models.EntityConfigurationStatementQueries
import com.sphereon.openid.fed.persistence.models.ForeignSubordinateStatementQueries
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.persistence.models.LogQueries
import com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.openid.fed.persistence.models.MetadataQueries
import com.sphereon.openid.fed.persistence.models.ReceivedTrustMarkQueries
import com.sphereon.openid.fed.persistence.models.SubordinateConstraintQueries
import com.sphereon.openid.fed.persistence.models.SubordinateJwkQueries
import com.sphereon.openid.fed.persistence.models.SubordinateMetadataQueries
import com.sphereon.openid.fed.persistence.models.SubordinateQueries
import com.sphereon.openid.fed.persistence.models.SubordinateStatementQueries
import com.sphereon.openid.fed.persistence.models.TrustAnchorHintQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuerQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkOwnerQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkTypeQueries

/**
 * Platform-specific implementation of the persistence layer for JVM.
 *
 * The database is opened and migrated on first use, not in the object initializer: a failed first use (for example a
 * database that is briefly unavailable or a migration that loses a race) leaves no permanently unusable object, and
 * the next use tries again.
 */
actual object Persistence {
    private val database = lazy { PersistenceDatabase() }

    actual val adminDocumentQueries: com.sphereon.openid.fed.persistence.models.AdminDocumentQueries
        get() = database.value.adminDocumentQueries
    actual val entityConfigurationStatementQueries: EntityConfigurationStatementQueries
        get() = database.value.entityConfigurationStatementQueries
    actual val accountMetadataPolicyRevisionQueries: AccountMetadataPolicyRevisionQueries
        get() = database.value.accountMetadataPolicyRevisionQueries
    actual val accountQueries: AccountQueries
        get() = database.value.accountQueries
    actual val accountSigningKeyQueries: AccountSigningKeyQueries
        get() = database.value.accountSigningKeyQueries
    actual val jwkQueries: JwkQueries
        get() = database.value.jwkQueries
    actual val subordinateQueries: SubordinateQueries
        get() = database.value.subordinateQueries
    actual val metadataQueries: MetadataQueries
        get() = database.value.metadataQueries
    actual val authorityHintQueries: AuthorityHintQueries
        get() = database.value.authorityHintQueries
    actual val critQueries: CritQueries
        get() = database.value.critQueries
    actual val subordinateStatementQueries: SubordinateStatementQueries
        get() = database.value.subordinateStatementQueries
    actual val subordinateJwkQueries: SubordinateJwkQueries
        get() = database.value.subordinateJwkQueries
    actual val subordinateMetadataQueries: SubordinateMetadataQueries
        get() = database.value.subordinateMetadataQueries
    actual val trustMarkTypeQueries: TrustMarkTypeQueries
        get() = database.value.trustMarkTypeQueries
    actual val trustMarkIssuerQueries: TrustMarkIssuerQueries
        get() = database.value.trustMarkIssuerQueries
    actual val trustMarkOwnerQueries: TrustMarkOwnerQueries
        get() = database.value.trustMarkOwnerQueries
    actual val trustMarkQueries: TrustMarkQueries
        get() = database.value.trustMarkQueries
    actual val receivedTrustMarkQueries: ReceivedTrustMarkQueries
        get() = database.value.receivedTrustMarkQueries
    actual val logQueries: LogQueries
        get() = database.value.logQueries
    actual val metadataPolicyQueries: MetadataPolicyQueries
        get() = database.value.metadataPolicyQueries
    actual val trustAnchorHintQueries: TrustAnchorHintQueries
        get() = database.value.trustAnchorHintQueries
    actual val subordinateConstraintQueries: SubordinateConstraintQueries
        get() = database.value.subordinateConstraintQueries
    actual val foreignSubordinateStatementQueries: ForeignSubordinateStatementQueries
        get() = database.value.foreignSubordinateStatementQueries
}

/**
 * Checks, without any DDL, that the schema another service migrated is at [requiredVersion]. The install-state
 * heuristic is left to the migrating service, so every service resolves the same identity mode default.
 */
internal fun verifySchema(driver: SqlDriver, requiredVersion: Long) {
    val current = try {
        driver.executeQuery(null, "SELECT version FROM schema_version ORDER BY version DESC LIMIT 1", parameters = 0, mapper = { cursor: SqlCursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
        }).value
    } catch (e: Exception) {
        throw IllegalStateException(
            "The OpenID Federation schema has not been created. Start the service that migrates it first.", e,
        )
    }
    check(current == requiredVersion) {
        "The OpenID Federation schema is at version ${current ?: 0}, but this library needs version $requiredVersion. " +
            "Start the service that migrates it first."
    }
}
