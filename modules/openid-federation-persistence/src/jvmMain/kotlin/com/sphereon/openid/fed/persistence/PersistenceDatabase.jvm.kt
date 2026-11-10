package com.sphereon.openid.fed.persistence

import app.cash.sqldelight.db.SqlDriver
import com.sphereon.openid.fed.persistence.config.DatabaseConfig
import com.sphereon.openid.fed.persistence.config.SchemaManagement
import com.sphereon.openid.fed.persistence.database.JavaUuidStringAdapter
import com.sphereon.openid.fed.persistence.database.PlatformSqlDriver
import com.sphereon.openid.fed.persistence.models.Account
import com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevision
import com.sphereon.openid.fed.persistence.models.AccountMetadataPolicyRevisionQueries
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKey
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.persistence.models.AuthorityHint
import com.sphereon.openid.fed.persistence.models.AuthorityHintQueries
import com.sphereon.openid.fed.persistence.models.Crit
import com.sphereon.openid.fed.persistence.models.CritQueries
import com.sphereon.openid.fed.persistence.models.EntityConfigurationStatement
import com.sphereon.openid.fed.persistence.models.EntityConfigurationStatementQueries
import com.sphereon.openid.fed.persistence.models.ForeignSubordinateStatement
import com.sphereon.openid.fed.persistence.models.ForeignSubordinateStatementQueries
import com.sphereon.openid.fed.persistence.models.Jwk
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.persistence.models.LogQueries
import com.sphereon.openid.fed.persistence.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.openid.fed.persistence.models.MetadataQueries
import com.sphereon.openid.fed.persistence.models.ReceivedTrustMark
import com.sphereon.openid.fed.persistence.models.ReceivedTrustMarkQueries
import com.sphereon.openid.fed.persistence.models.Subordinate
import com.sphereon.openid.fed.persistence.models.SubordinateJwk
import com.sphereon.openid.fed.persistence.models.SubordinateJwkQueries
import com.sphereon.openid.fed.persistence.models.SubordinateMetadata
import com.sphereon.openid.fed.persistence.models.SubordinateMetadataQueries
import com.sphereon.openid.fed.persistence.models.SubordinateQueries
import com.sphereon.openid.fed.persistence.models.SubordinateStatement
import com.sphereon.openid.fed.persistence.models.SubordinateStatementQueries
import com.sphereon.openid.fed.persistence.models.TrustMark
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuer
import com.sphereon.openid.fed.persistence.models.TrustMarkIssuerQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkType
import com.sphereon.openid.fed.persistence.models.TrustMarkTypeRevision
import com.sphereon.openid.fed.persistence.models.TrustMarkOwner
import com.sphereon.openid.fed.persistence.models.TrustMarkOwnerQueries
import com.sphereon.openid.fed.persistence.models.TrustAnchorHint
import com.sphereon.openid.fed.persistence.models.TrustAnchorHintQueries
import com.sphereon.openid.fed.persistence.models.SubordinateConstraint
import com.sphereon.openid.fed.persistence.models.SubordinateConstraintQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkTypeQueries

/**
 * One initialized connection to the federation database: the driver, the applied or verified schema, and the query
 * interfaces. [Persistence] creates it on first use and, if creation fails, creates it again on the next use.
 */
internal class PersistenceDatabase {
    val adminDocumentQueries: com.sphereon.openid.fed.persistence.models.AdminDocumentQueries
    val entityConfigurationStatementQueries: EntityConfigurationStatementQueries
    val accountMetadataPolicyRevisionQueries: AccountMetadataPolicyRevisionQueries
    val accountQueries: AccountQueries
    val accountSigningKeyQueries: AccountSigningKeyQueries
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
    val trustMarkOwnerQueries: TrustMarkOwnerQueries
    val trustMarkQueries: TrustMarkQueries
    val receivedTrustMarkQueries: ReceivedTrustMarkQueries
    val logQueries: LogQueries
    val metadataPolicyQueries: MetadataPolicyQueries
    val trustAnchorHintQueries: TrustAnchorHintQueries
    val subordinateConstraintQueries: SubordinateConstraintQueries
    val foreignSubordinateStatementQueries: ForeignSubordinateStatementQueries


    private val driver: SqlDriver
    private val database: Database

    init {
        val config = DatabaseConfig()
        driver = createDriver(config)
        when (config.schemaManagement) {
            SchemaManagement.MIGRATE -> runMigrations(driver)
            SchemaManagement.VERIFY -> verifySchema(driver, Database.Schema.version)
        }
        // We are mapping the Postgres UUID types to OpenAPI model string types
        database = Database(
            driver,
            AccountAdapter = Account.Adapter(JavaUuidStringAdapter),
            AccountMetadataPolicyRevisionAdapter = AccountMetadataPolicyRevision.Adapter(JavaUuidStringAdapter),
            AccountSigningKeyAdapter = AccountSigningKey.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            AuthorityHintAdapter = AuthorityHint.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            CritAdapter = Crit.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            EntityConfigurationStatementAdapter = EntityConfigurationStatement.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            JwkAdapter = Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            MetadataAdapter = com.sphereon.openid.fed.persistence.models.Metadata.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            ReceivedTrustMarkAdapter = ReceivedTrustMark.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            SubordinateAdapter = Subordinate.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            SubordinateJwkAdapter = SubordinateJwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            SubordinateMetadataAdapter = SubordinateMetadata.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            SubordinateStatementAdapter = SubordinateStatement.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkAdapter = TrustMark.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkIssuerAdapter = TrustMarkIssuer.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkOwnerAdapter = TrustMarkOwner.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkTypeAdapter = TrustMarkType.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustMarkTypeRevisionAdapter = TrustMarkTypeRevision.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            MetadataPolicyAdapter = MetadataPolicy.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
            TrustAnchorHintAdapter = TrustAnchorHint.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            SubordinateConstraintAdapter = SubordinateConstraint.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
            ForeignSubordinateStatementAdapter = ForeignSubordinateStatement.Adapter(
                JavaUuidStringAdapter,
                JavaUuidStringAdapter
            ),
        )

        adminDocumentQueries = database.adminDocumentQueries
        accountQueries = database.accountQueries
        accountMetadataPolicyRevisionQueries = database.accountMetadataPolicyRevisionQueries
        accountSigningKeyQueries = database.accountSigningKeyQueries
        entityConfigurationStatementQueries = database.entityConfigurationStatementQueries
        jwkQueries = database.jwkQueries
        subordinateQueries = database.subordinateQueries
        metadataQueries = database.metadataQueries
        authorityHintQueries = database.authorityHintQueries
        critQueries = database.critQueries
        subordinateStatementQueries = database.subordinateStatementQueries
        subordinateJwkQueries = database.subordinateJwkQueries
        subordinateMetadataQueries = database.subordinateMetadataQueries
        trustMarkTypeQueries = database.trustMarkTypeQueries
        trustMarkIssuerQueries = database.trustMarkIssuerQueries
        trustMarkOwnerQueries = database.trustMarkOwnerQueries
        trustMarkQueries = database.trustMarkQueries
        receivedTrustMarkQueries = database.receivedTrustMarkQueries
        logQueries = database.logQueries
        metadataPolicyQueries = database.metadataPolicyQueries
        trustAnchorHintQueries = database.trustAnchorHintQueries
        subordinateConstraintQueries = database.subordinateConstraintQueries
        foreignSubordinateStatementQueries = database.foreignSubordinateStatementQueries
    }

    private fun createDriver(config: DatabaseConfig): SqlDriver {
        return PlatformSqlDriver().createPostgresDriver(
            config.url,
            config.username,
            config.password
        )
    }
}
