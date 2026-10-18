package com.sphereon.openid.fed.services.command.trustMark

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.crypto.core.generic.SignatureAlgorithm
import com.sphereon.crypto.core.kms.KeyManagerService
import com.sphereon.crypto.core.kms.asKeyManagerServiceGraph
import com.sphereon.crypto.jose.jws.JwtService
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderConfig
import com.sphereon.crypto.kms.provider.software.SoftwareKmsProviderFactoryImpl
import com.sphereon.di.context.PrincipalType
import com.sphereon.di.session.SessionInstance
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.TrustMarkTypeNotFoundError
import com.sphereon.openid.fed.core.error.TrustMarkTypeVersionConflictError
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkRequest
import com.sphereon.openid.fed.openapi.models.CreateTrustMarkType
import com.sphereon.openid.fed.openapi.models.TrustMarkTypeDefinition
import com.sphereon.openid.fed.persistence.Database
import com.sphereon.openid.fed.persistence.database.JavaUuidStringAdapter
import com.sphereon.openid.fed.persistence.models.Account
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.AccountSigningKey as AccountSigningKeyEntity
import com.sphereon.openid.fed.persistence.models.AccountSigningKeyQueries
import com.sphereon.openid.fed.persistence.models.Jwk
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.persistence.models.TrustMark
import com.sphereon.openid.fed.persistence.models.TrustMarkQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkType
import com.sphereon.openid.fed.persistence.models.TrustMarkTypeQueries
import com.sphereon.openid.fed.persistence.models.TrustMarkTypeRevision
import com.sphereon.openid.fed.services.command.jwk.FindAccountSigningKeySelectionCommandImpl
import com.sphereon.openid.fed.services.command.jwk.ResolveAccountSigningKeyCommandImpl
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trust Mark type definitions, their versions and revisions, and their use as issuance defaults, on PostgreSQL. */
class TrustMarkTypeDefinitionCommandTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null

        @JvmStatic @BeforeClass
        fun startPostgres() {
            assertTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
                "Trust mark type definition tests require Docker and an isolated PostgreSQL container")
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("oidfed_trust_mark_types")
                withUsername("test")
                withPassword("test")
                start()
            }
        }

        @JvmStatic @AfterClass
        fun stopPostgres() {
            postgres?.stop()
            postgres = null
        }
    }

    private val identifier = "https://issuer.example"
    private val typeIdentifier = "https://issuer.example/trust-marks/certified"
    private lateinit var driver: SqlDriver
    private lateinit var source: PGSimpleDataSource
    private lateinit var schema: String
    private lateinit var session: SessionInstance
    private lateinit var execution: SessionExecution
    private lateinit var accountQueries: AccountQueries
    private lateinit var jwkQueries: JwkQueries
    private lateinit var selectionQueries: AccountSigningKeyQueries
    private lateinit var typeQueries: TrustMarkTypeQueries
    private lateinit var trustMarkQueries: TrustMarkQueries
    private lateinit var keyManager: KeyManagerService
    private lateinit var jwtService: JwtService
    private lateinit var resolver: TenantContextResolver
    private lateinit var accountId: String

    @Before
    fun setUp(): Unit = kotlinx.coroutines.runBlocking {
        schema = createSchema()
        source = dataSource(schema)
        driver = source.asJdbcDriver()
        Database.Schema.migrate(driver, 0L, Database.Schema.version)
        accountQueries = AccountQueries(driver, Account.Adapter(JavaUuidStringAdapter))
        jwkQueries = JwkQueries(driver, Jwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
        selectionQueries = AccountSigningKeyQueries(driver,
            AccountSigningKeyEntity.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
        typeQueries = typeQueries(driver)
        trustMarkQueries = TrustMarkQueries(driver, TrustMarkAdapter = TrustMark.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
        val app = createExactSuperiorEvidenceTestGraph(this@TrustMarkTypeDefinitionCommandTest)
        session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "trust-mark-types-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS)
        execution = session.asCoreApiServiceGraph().serviceExecution
        val provider = (app as SoftwareKmsProviderFactoryImpl.Graph).softwareKmsProvider.create(
            SoftwareKmsProviderConfig(id = "trust-mark-types-provider-" + UUID.randomUUID()), execution)
        keyManager = session.graph.asKeyManagerServiceGraph().keyManagerService
        keyManager.registerProvider(provider, makeDefaultKms = true)
        jwtService = (session.graph as com.sphereon.crypto.jose.jws.JwtServiceImpl.Graph).jwtService

        accountId = accountQueries.create("trust-mark-types-" + UUID.randomUUID(), identifier).executeAsOne().id
        val generated = keyManager.generateKeyAsync(alg = SignatureAlgorithm.ECDSA_SHA256)
        val signingKey = jwkQueries.create(accountId, "ES256", requireNotNull(generated.kid), generated.providerId,
            requireNotNull(generated.alias), generated.jose.publicJwk.toJsonString()).executeAsOne()
        selectionQueries.upsert(accountId, signingKey.id, 1L).executeAsOne()
        resolver = mockk()
        coEvery { resolver.resolveIdentifier(accountId) } returns identifier
    }

    @After
    fun tearDown() {
        try {
            if (::session.isInitialized) session.destroy()
        } finally {
            try {
                if (::driver.isInitialized) driver.close()
            } finally {
                if (::schema.isInitialized) dropSchema(schema)
            }
        }
    }

    private fun typeQueries(driver: SqlDriver) = TrustMarkTypeQueries(
        driver,
        TrustMarkTypeAdapter = TrustMarkType.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
        TrustMarkTypeRevisionAdapter = TrustMarkTypeRevision.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
    )

    private fun createSchema(): String {
        val name = "trust_mark_types_" + UUID.randomUUID().toString().replace("-", "")
        val container = requireNotNull(postgres)
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $name") }
        }
        return name
    }

    private fun dropSchema(name: String) {
        val container = requireNotNull(postgres)
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("DROP SCHEMA $name CASCADE") }
        }
    }

    private fun dataSource(name: String): PGSimpleDataSource {
        val container = requireNotNull(postgres)
        val separator = if ('?' in container.jdbcUrl) '&' else '?'
        return PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl + separator + "currentSchema=" + name)
            user = container.username
            password = container.password
        }
    }

    private fun create() = CreateTrustMarkTypeCommandImpl(execution, typeQueries)
    private fun findById() = FindTrustMarkTypeByIdCommandImpl(execution, typeQueries)
    private fun findAll() = FindAllTrustMarkTypesByAccountCommandImpl(execution, typeQueries)
    private fun delete() = DeleteTrustMarkTypeCommandImpl(execution, typeQueries)
    private fun update() = UpdateTrustMarkTypeDefinitionCommandImpl(execution, typeQueries)
    private fun revisions() = ListTrustMarkTypeRevisionsCommandImpl(execution, typeQueries)
    private fun issue() = CreateTrustMarkCommandImpl(
        execution, jwtService, resolver,
        ResolveAccountSigningKeyCommandImpl(execution,
            FindAccountSigningKeySelectionCommandImpl(execution, accountQueries, selectionQueries), jwkQueries),
        trustMarkQueries, typeQueries,
    )

    private val extensions: JsonObject = buildJsonObject {
        put("audience", "public")
        put("nested", buildJsonObject { put("level", 3); put("tags", buildJsonArray { add(Json.parseToJsonElement("\"a\"")) }) })
    }

    private fun definition(
        name: String = "Certified",
        description: String? = "Entities that passed certification",
        ref: String? = "https://issuer.example/certified",
        logoUri: String? = "https://issuer.example/certified.svg",
        lifetimeSeconds: Long? = 3600L,
        applicableEntityTypes: List<String>? = listOf("openid_relying_party"),
        extensions: JsonObject? = this.extensions,
    ) = TrustMarkTypeDefinition(
        name = name,
        description = description,
        ref = ref,
        logoUri = logoUri,
        lifetimeSeconds = lifetimeSeconds,
        applicableEntityTypes = applicableEntityTypes,
        extensions = extensions,
    )

    private suspend fun createType(definition: TrustMarkTypeDefinition? = null, typeIdentifier: String = this.typeIdentifier) =
        create().execute(CreateTrustMarkTypeArgs(accountId, CreateTrustMarkType(identifier = typeIdentifier, definition = definition)))

    private fun payload(jwt: String): JsonObject = Json.parseToJsonElement(
        String(Base64.getUrlDecoder().decode(jwt.split('.')[1]), Charsets.UTF_8)).jsonObject

    @Test
    fun typeCreatedWithoutDefinitionStartsAtVersionOneWithAnEmptyRevision() = runTest {
        val created = createType()
        assertTrue(created.isOk)
        assertNull(created.value.definition)
        assertEquals(1L, created.value.version)
        val found = findById().execute(FindTrustMarkTypeByIdArgs(accountId, created.value.id))
        assertTrue(found.isOk)
        assertNull(found.value.definition)
        assertEquals(1L, found.value.version)
        val history = revisions().execute(ListTrustMarkTypeRevisionsArgs(accountId, created.value.id))
        assertTrue(history.isOk)
        assertEquals(listOf(1L), history.value.map { it.version })
        assertNull(history.value.single().definition)
    }

    @Test
    fun typeCreatedWithDefinitionReturnsTheNormalizedDefinitionEverywhere() = runTest {
        val created = createType(definition(name = "  Certified  ", applicableEntityTypes = listOf(" openid_relying_party ")))
        assertTrue(created.isOk)
        val expected = definition()
        assertEquals("Certified", created.value.definition!!.name)
        assertEquals(listOf("openid_relying_party"), created.value.definition!!.applicableEntityTypes)
        assertEquals(expected.ref, created.value.definition!!.ref)
        assertEquals(expected.logoUri, created.value.definition!!.logoUri)
        assertEquals(3600L, created.value.definition!!.lifetimeSeconds)
        assertEquals(extensions, created.value.definition!!.extensions, "extensions must be returned unchanged")
        assertEquals(1L, created.value.version)

        val found = findById().execute(FindTrustMarkTypeByIdArgs(accountId, created.value.id)).value
        assertEquals(created.value.definition, found.definition)
        val listed = findAll().execute(FindAllTrustMarkTypesByAccountArgs(accountId)).value.single()
        assertEquals(created.value.definition, listed.definition)
        assertEquals(1L, listed.version)
        val revision = revisions().execute(ListTrustMarkTypeRevisionsArgs(accountId, created.value.id)).value.single()
        assertEquals(1L, revision.version)
        assertEquals(created.value.definition, revision.definition)
        assertNotNull(revision.createdAt)
    }

    @Test
    fun updateIncrementsVersionAppendsRevisionAndKeepsIdentifierAndDelegation() = runTest {
        val created = createType(definition()).value
        typeQueries.setDelegation("delegation-jwt", accountId, created.id).executeAsOne()
        val before = typeQueries.findByAccountIdAndId(accountId, created.id).executeAsOne()

        val changed = definition(name = "Certified v2", lifetimeSeconds = 7200L, extensions = null)
        val updated = update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, created.id, 1L, changed))
        assertTrue(updated.isOk)
        assertEquals(2L, updated.value.version)
        assertEquals("Certified v2", updated.value.definition!!.name)
        assertEquals(7200L, updated.value.definition!!.lifetimeSeconds)
        assertNull(updated.value.definition!!.extensions)
        assertEquals(created.identifier, updated.value.identifier)
        assertNotNull(updated.value.updatedAt)

        val after = typeQueries.findByAccountIdAndId(accountId, created.id).executeAsOne()
        assertEquals("delegation-jwt", after.delegation)
        assertEquals(before.identifier, after.identifier)
        assertEquals(before.account_id, after.account_id)
        assertTrue(after.updated_at!! >= before.updated_at!!)

        val third = update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, created.id, 2L, definition(name = "Certified v3")))
        assertEquals(3L, third.value.version)
        val history = revisions().execute(ListTrustMarkTypeRevisionsArgs(accountId, created.id)).value
        assertEquals(listOf(1L, 2L, 3L), history.map { it.version })
        assertEquals(listOf("Certified", "Certified v2", "Certified v3"), history.map { it.definition!!.name })
        assertEquals(extensions, history.first().definition!!.extensions)
    }

    @Test
    fun updateOfATypeWithoutDefinitionAddsOne() = runTest {
        val created = createType().value
        val updated = update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, created.id, 1L, definition()))
        assertTrue(updated.isOk)
        assertEquals(2L, updated.value.version)
        val history = revisions().execute(ListTrustMarkTypeRevisionsArgs(accountId, created.id)).value
        assertEquals(listOf(1L, 2L), history.map { it.version })
        assertNull(history[0].definition)
        assertEquals("Certified", history[1].definition!!.name)
    }

    @Test
    fun staleExpectedVersionIsAConflictAndChangesNothing() = runTest {
        val created = createType(definition()).value
        assertTrue(update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, created.id, 1L, definition(name = "Second"))).isOk)
        val before = typeQueries.findByAccountIdAndId(accountId, created.id).executeAsOne()

        val stale = update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, created.id, 1L, definition(name = "Lost")))
        assertTrue(stale.isErr)
        val conflict = stale.error as TrustMarkTypeVersionConflictError
        assertEquals(TrustMarkTypeVersionConflictError.ERROR_CODE, conflict.code)
        assertEquals(409, conflict.httpStatusValue)
        assertEquals(1L, conflict.expectedVersion)
        assertEquals(2L, conflict.currentVersion)
        assertTrue(conflict.message.defaultMessage.contains("1"))
        assertTrue(conflict.message.defaultMessage.contains("2"))

        val ahead = update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, created.id, 5L, definition(name = "Ahead")))
        assertTrue(ahead.error is TrustMarkTypeVersionConflictError)

        assertEquals(before, typeQueries.findByAccountIdAndId(accountId, created.id).executeAsOne())
        assertEquals(listOf(1L, 2L), revisions().execute(ListTrustMarkTypeRevisionsArgs(accountId, created.id)).value.map { it.version })
    }

    @Test
    fun unknownForeignAndDeletedTypesAreNotFound() = runTest {
        val unknown = UUID.randomUUID().toString()
        assertTrue(update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, unknown, 1L, definition())).error is TrustMarkTypeNotFoundError)
        assertTrue(update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, "not-a-uuid", 1L, definition())).error is TrustMarkTypeNotFoundError)
        assertTrue(revisions().execute(ListTrustMarkTypeRevisionsArgs(accountId, unknown)).error is TrustMarkTypeNotFoundError)
        assertTrue(findById().execute(FindTrustMarkTypeByIdArgs(accountId, "not-a-uuid")).error is TrustMarkTypeNotFoundError)

        val other = accountQueries.create("other-" + UUID.randomUUID(), "https://other.example").executeAsOne().id
        val created = createType(definition()).value
        assertTrue(update().execute(UpdateTrustMarkTypeDefinitionArgs(other, created.id, 1L, definition())).error is TrustMarkTypeNotFoundError)
        assertTrue(revisions().execute(ListTrustMarkTypeRevisionsArgs(other, created.id)).error is TrustMarkTypeNotFoundError)

        assertTrue(delete().execute(DeleteTrustMarkTypeArgs(accountId, created.id)).isOk)
        assertTrue(update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, created.id, 1L, definition())).error is TrustMarkTypeNotFoundError)
    }

    @Test
    fun revisionsRemainAfterTheTypeIsDeleted() = runTest {
        val created = createType(definition()).value
        assertTrue(update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, created.id, 1L, definition(name = "Second"))).isOk)
        val deleted = delete().execute(DeleteTrustMarkTypeArgs(accountId, created.id))
        assertTrue(deleted.isOk)
        assertEquals(2L, deleted.value.version)
        assertEquals(2, typeQueries.findRevisionsByTypeId(created.id).executeAsList().size)
        val history = revisions().execute(ListTrustMarkTypeRevisionsArgs(accountId, created.id))
        assertTrue(history.isOk)
        assertEquals(listOf(1L, 2L), history.value.map { it.version })
        // The identifier can be reused by a new type, which starts its own history.
        val recreated = createType(definition(name = "Recreated")).value
        assertEquals(1L, recreated.version)
        assertEquals(listOf(1L), revisions().execute(ListTrustMarkTypeRevisionsArgs(accountId, recreated.id)).value.map { it.version })
    }

    @Test
    fun invalidDefinitionsAreRejectedOnCreateAndUpdate() = runTest {
        val existing = createType(definition(), "https://issuer.example/trust-marks/existing").value
        val invalid = listOf(
            definition(name = "   "),
            definition(name = "n".repeat(201)),
            definition(description = "d".repeat(4001)),
            definition(ref = "http://issuer.example/certified"),
            definition(ref = "/certified"),
            definition(ref = "https://"),
            definition(logoUri = "issuer.example/logo.svg"),
            definition(logoUri = "https://issuer.example/logo with space.svg"),
            definition(lifetimeSeconds = 59L),
            definition(lifetimeSeconds = 63_072_001L),
            definition(applicableEntityTypes = listOf("openid_relying_party", " ")),
            definition(applicableEntityTypes = listOf("openid_relying_party", " openid_relying_party")),
        )
        for ((index, candidate) in invalid.withIndex()) {
            val created = createType(candidate, "https://issuer.example/trust-marks/invalid-$index")
            assertTrue(created.isErr, "create must reject $candidate")
            assertEquals(InvalidRequestError.ERROR_CODE, created.error.code)
            val updated = update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, existing.id, 1L, candidate))
            assertTrue(updated.isErr, "update must reject $candidate")
            assertEquals(InvalidRequestError.ERROR_CODE, updated.error.code)
        }
        assertEquals(1, findAll().execute(FindAllTrustMarkTypesByAccountArgs(accountId)).value.size)
        assertEquals(1L, findById().execute(FindTrustMarkTypeByIdArgs(accountId, existing.id)).value.version)

        val boundaries = definition(name = "n".repeat(200), description = "d".repeat(4000), lifetimeSeconds = 60L,
            applicableEntityTypes = emptyList(), ref = null, logoUri = null, extensions = JsonObject(emptyMap()))
        assertTrue(update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, existing.id, 1L, boundaries)).isOk)
        assertTrue(update().execute(UpdateTrustMarkTypeDefinitionArgs(accountId, existing.id, 2L,
            definition(lifetimeSeconds = 63_072_000L, applicableEntityTypes = null))).isOk)
        assertEquals(emptyList(), findById().execute(FindTrustMarkTypeByIdArgs(accountId, existing.id)).value.definition!!.applicableEntityTypes)
    }

    @Test
    fun issuanceUsesTheDefinitionForMissingLogoRefAndExp() = runTest {
        createType(definition())
        val iat = 1_800_000_000.0
        val issued = issue().execute(CreateTrustMarkArgs(accountId,
            CreateTrustMarkRequest(sub = "https://subject.example", trustMarkType = typeIdentifier, iat = iat),
            System.currentTimeMillis()))
        assertTrue(issued.isOk, issued.toString())
        val claims = payload(issued.value.trustMarkValue)
        assertEquals("https://issuer.example/certified.svg", claims["logo_uri"]!!.jsonPrimitive.content)
        assertEquals("https://issuer.example/certified", claims["ref"]!!.jsonPrimitive.content)
        assertEquals(iat + 3600, claims["exp"]!!.jsonPrimitive.double)
        assertEquals(iat + 3600, issued.value.exp)
        assertEquals((iat + 3600).toInt(), trustMarkQueries.findByAccountIdAndId(accountId, issued.value.id!!).executeAsOne().exp)
    }

    @Test
    fun explicitIssuanceValuesWinOverTheDefinition() = runTest {
        createType(definition())
        val iat = 1_800_000_000.0
        val issued = issue().execute(CreateTrustMarkArgs(accountId,
            CreateTrustMarkRequest(sub = "https://subject.example", trustMarkType = typeIdentifier, iat = iat,
                logoUri = "https://explicit.example/logo.png", ref = "https://explicit.example/ref", exp = iat + 60),
            System.currentTimeMillis()))
        assertTrue(issued.isOk, issued.toString())
        val claims = payload(issued.value.trustMarkValue)
        assertEquals("https://explicit.example/logo.png", claims["logo_uri"]!!.jsonPrimitive.content)
        assertEquals("https://explicit.example/ref", claims["ref"]!!.jsonPrimitive.content)
        assertEquals(iat + 60, claims["exp"]!!.jsonPrimitive.double)
    }

    @Test
    fun typesWithoutDefinitionIssueAsBefore() = runTest {
        createType()
        val issued = issue().execute(CreateTrustMarkArgs(accountId,
            CreateTrustMarkRequest(sub = "https://subject.example", trustMarkType = typeIdentifier, iat = 1_800_000_000.0),
            System.currentTimeMillis()))
        assertTrue(issued.isOk, issued.toString())
        val claims = payload(issued.value.trustMarkValue)
        assertFalse(claims.containsKey("logo_uri"))
        assertFalse(claims.containsKey("ref"))
        assertFalse(claims.containsKey("exp"))
        assertNull(issued.value.exp)
    }

    @Test
    fun migrationTwentySixBackfillsOneEmptyRevisionPerType() = runTest {
        val upgradeSchema = createSchema()
        val upgradeSource = dataSource(upgradeSchema)
        val upgradeDriver = upgradeSource.asJdbcDriver()
        try {
            // SQLDelight applies migration n when upgrading schema version n to n + 1.
            Database.Schema.migrate(upgradeDriver, 0L, 26L)
            val owner = UUID.randomUUID().toString()
            val active = UUID.randomUUID().toString()
            val deleted = UUID.randomUUID().toString()
            upgradeSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO Account (id, username, identifier) VALUES (?::uuid, ?, ?)").use {
                    it.setString(1, owner); it.setString(2, "upgrade-" + UUID.randomUUID()); it.setString(3, identifier)
                    it.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO TrustMarkType (id, account_id, identifier, deleted_at) VALUES (?::uuid, ?::uuid, ?, ?)",
                ).use { statement ->
                    for ((id, deletedAt) in listOf(active to null, deleted to java.sql.Timestamp(0L))) {
                        statement.setString(1, id); statement.setString(2, owner); statement.setString(3, "$typeIdentifier/$id")
                        statement.setTimestamp(4, deletedAt)
                        statement.executeUpdate()
                    }
                }
            }
            Database.Schema.migrate(upgradeDriver, 26L, 27L)
            val upgraded = typeQueries(upgradeDriver)
            val type = upgraded.findByAccountIdAndId(owner, active).executeAsOne()
            assertEquals(1L, type.version)
            assertNull(type.definition)
            for (id in listOf(active, deleted)) {
                val revision = upgraded.findRevisionsByTypeId(id).executeAsList().single()
                assertEquals(1L, revision.version)
                assertNull(revision.definition)
            }
            upgradeSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    val rejected = runCatching { statement.execute("UPDATE TrustMarkType SET version = 0") }
                    assertTrue(rejected.isFailure, "version must stay at least 1")
                }
            }
        } finally {
            try { upgradeDriver.close() } finally { dropSchema(upgradeSchema) }
        }
    }
}
