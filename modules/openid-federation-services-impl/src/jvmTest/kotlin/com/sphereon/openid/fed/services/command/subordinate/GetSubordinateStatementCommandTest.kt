package com.sphereon.openid.fed.services.command.subordinate

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.http.GenericHttpRequest
import com.sphereon.core.api.session.asCoreApiServiceGraph
import com.sphereon.di.context.PrincipalType
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.core.error.InvalidRequestError
import com.sphereon.openid.fed.core.error.ServerError
import com.sphereon.openid.fed.core.error.SubordinateNotFoundError
import com.sphereon.openid.fed.core.tenant.TenantContextResolver
import com.sphereon.openid.fed.openapi.models.Jwk
import com.sphereon.openid.fed.openapi.models.SubordinateStatement
import com.sphereon.openid.fed.persistence.Database
import com.sphereon.openid.fed.persistence.database.JavaUuidStringAdapter
import com.sphereon.openid.fed.persistence.models.Account
import com.sphereon.openid.fed.persistence.models.AccountQueries
import com.sphereon.openid.fed.persistence.models.MetadataPolicy
import com.sphereon.openid.fed.persistence.models.MetadataPolicyQueries
import com.sphereon.openid.fed.persistence.models.Subordinate
import com.sphereon.openid.fed.persistence.models.SubordinateConstraint
import com.sphereon.openid.fed.persistence.models.SubordinateConstraintQueries
import com.sphereon.openid.fed.persistence.models.SubordinateJwk
import com.sphereon.openid.fed.persistence.models.SubordinateJwkQueries
import com.sphereon.openid.fed.persistence.models.SubordinateMetadata
import com.sphereon.openid.fed.persistence.models.SubordinateMetadataQueries
import com.sphereon.openid.fed.persistence.models.SubordinateQueries
import com.sphereon.openid.fed.persistence.models.Jwk as StoredJwk
import com.sphereon.openid.fed.persistence.models.JwkQueries
import com.sphereon.openid.fed.services.command.resolution.createExactSuperiorEvidenceTestGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real migrated queries, isolated schema, standard session, and unchanged JDBC statements/binders. */
class GetSubordinateStatementCommandTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null
        private const val SUPERIOR = "https://superior.example/Case/%2F"
        private const val CHILD = "https://child.example/Case/%2F"
        private const val FOREIGN_SUPERIOR = "https://foreign-superior.example"
        private const val FOREIGN_CHILD = "https://foreign-child.example"
        private const val CHILD_KEY_ONE = """{"kty":"RSA","kid":"child-one","n":"AQID","e":"AQAB","alg":"RS256","use":"sig"}"""
        private const val CHILD_KEY_TWO = """{"kty":"RSA","kid":"child-two","n":"BAUG","e":"AQAB","alg":"RS256","use":"sig"}"""
        private const val FOREIGN_KEY = """{"kty":"RSA","kid":"foreign-one","n":"BwgJ","e":"AQAB"}"""
        private const val PARENT_KEY = """{"kty":"RSA","kid":"superior-key","n":"CgsM","e":"AQAB"}"""
        private const val METADATA = """{"issuer":"https://child.example/Case/%2F","x_unknown":{"null":null,"ordered":[2,1],"typed":true}}"""
        private const val POLICY = """{"issuer":{"value":"https://child.example/Case/%2F","x_operator":{"nested":[null,"literal",7]}}}"""
        private const val SECOND_POLICY = """{"organization_name":{"default":"Child operator","x_operator":false}}"""
        private const val CONSTRAINTS = """{"max_path_length":2,"allowed_entity_types":["openid_credential_issuer","openid_credential_verifier"]}"""

        @JvmStatic @BeforeClass
        fun startPostgres() {
            assertTrue(
                runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
                "Subordinate read ownership proof requires Docker and live PostgreSQL",
            )
            postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                withDatabaseName("oidfed_subordinate_read")
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

    private lateinit var source: PGSimpleDataSource
    private lateinit var reads: ReadRecordingDataSource
    private lateinit var driver: SqlDriver
    private lateinit var execution: SessionExecution
    private lateinit var subordinateQueries: SubordinateQueries
    private lateinit var keyQueries: SubordinateJwkQueries
    private lateinit var metadataQueries: SubordinateMetadataQueries
    private lateinit var constraintQueries: SubordinateConstraintQueries
    private lateinit var policyQueries: MetadataPolicyQueries
    private lateinit var ownAccount: String
    private lateinit var foreignAccount: String
    private lateinit var ownChild: String
    private lateinit var foreignChild: String
    private val resolverCalls = mutableListOf<String>()
    private var resolverFailure: Throwable? = null

    private val resolver = object : TenantContextResolver {
        override suspend fun resolveTenantId(request: GenericHttpRequest): String? =
            error("GET must not resolve request ingress")
        override suspend fun resolveTenantIdByName(name: String): String? =
            error("GET must not resolve a route name")
        override suspend fun resolveIdentifier(tenantId: String): String? {
            resolverCalls += tenantId
            resolverFailure?.let { throw it }
            return when (tenantId) {
                ownAccount -> SUPERIOR
                foreignAccount -> FOREIGN_SUPERIOR
                else -> null
            }
        }
    }

    @Before
    fun setUp() {
        val container = requireNotNull(postgres)
        val schema = "subordinate_read_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA " + schema) }
        }
        val separator = if ('?' in container.jdbcUrl) '&' else '?'
        source = PGSimpleDataSource().apply {
            setUrl(container.jdbcUrl + separator + "currentSchema=" + schema)
            user = container.username
            password = container.password
        }
        reads = ReadRecordingDataSource(source)
        driver = reads.asJdbcDriver()
        Database.Schema.migrate(driver, 0L, Database.Schema.version)
        val accounts = AccountQueries(driver, Account.Adapter(JavaUuidStringAdapter))
        subordinateQueries = SubordinateQueries(
            driver, Subordinate.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
        )
        keyQueries = SubordinateJwkQueries(
            driver, SubordinateJwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
        )
        metadataQueries = SubordinateMetadataQueries(
            driver, SubordinateMetadata.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter, JavaUuidStringAdapter),
        )
        constraintQueries = SubordinateConstraintQueries(
            driver, SubordinateConstraint.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter, JavaUuidStringAdapter),
        )
        policyQueries = MetadataPolicyQueries(
            driver, MetadataPolicy.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter),
        )
        ownAccount = accounts.create("own-" + UUID.randomUUID(), SUPERIOR).executeAsOne().id
        foreignAccount = accounts.create("foreign-" + UUID.randomUUID(), FOREIGN_SUPERIOR).executeAsOne().id
        ownChild = subordinateQueries.create(ownAccount, CHILD).executeAsOne().id
        foreignChild = subordinateQueries.create(foreignAccount, FOREIGN_CHILD).executeAsOne().id
        keyQueries.create(ownChild, CHILD_KEY_ONE).executeAsOne()
        keyQueries.create(ownChild, CHILD_KEY_TWO).executeAsOne()
        keyQueries.create(foreignChild, FOREIGN_KEY).executeAsOne()
        JwkQueries(driver, StoredJwk.Adapter(JavaUuidStringAdapter, JavaUuidStringAdapter))
            .create(ownAccount, "RS256", "superior-key", "memory", "superior-ref", PARENT_KEY).executeAsOne()
        val app = createExactSuperiorEvidenceTestGraph(this)
        val session = app.userContextManager.getAnonymous().sessionContextManager.createOrGetFromId(
            "subordinate-read-" + UUID.randomUUID(), principalType = PrincipalType.ANONYMOUS,
        )
        execution = session.asCoreApiServiceGraph().serviceExecution
        resolverCalls.clear()
        resolverFailure = null
    }

    @After
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
    }

    private fun command() = GetSubordinateStatementCommandImpl(
        execution, resolver, subordinateQueries, keyQueries, metadataQueries, constraintQueries, policyQueries,
        configuredLifetime(86_400),
    )

    private fun configuredLifetime(seconds: Long?) =
        object : com.sphereon.openid.fed.client.config.AppConfigOidfConfigBinder(io.mockk.mockk(relaxed = true)) {
            override fun getFederationConfig() =
                com.sphereon.openid.fed.core.config.FederationConfig(statementLifetimeSeconds = seconds)
        }

    // Complete persisted values (including deleted rows and timestamps), not generated output expectations.
    private fun rows(): Map<String, List<String>> = source.connection.use { connection ->
        listOf(
            "Account", "Jwk", "Subordinate", "SubordinateJwk", "SubordinateMetadata",
            "SubordinateConstraint", "MetadataPolicy", "SubordinateStatement", "EntityConfigurationStatement",
        ).associateWith { table ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT row_to_json(t)::text FROM " + table + " AS t ORDER BY id").use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }
    }

    private suspend fun observe(id: String = ownChild): IdkResult<SubordinateStatement, FederationError> {
        val before = rows()
        reads.enabled = true
        return try {
            command().execute(GetSubordinateStatementArgs(ownAccount, id))
        } finally {
            reads.enabled = false
            assertEquals(before, rows(), "GET must preserve complete own/foreign rows and publication history")
            assertTrue(reads.sql.all { it.trimStart().startsWith("SELECT", ignoreCase = true) },
                "GET must not prepare any mutation")
        }
    }

    private suspend fun assertNotFoundWithoutDownstream(id: String) {
        val result = observe(id)
        assertEquals(emptyList(), reads.tables.filter { it != "Subordinate" },
            "Foreign/missing/deleted subordinate must cause zero downstream reads")
        assertEquals(emptyList(), resolverCalls, "Rejected child must not resolve the issuer")
        assertTrue(result.isErr, "Rejected child must not produce a statement")
        assertIs<SubordinateNotFoundError>(result.error)
        assertEquals(listOf("Subordinate"), reads.tables)
    }

    private suspend fun assertServerError() {
        val result = observe()
        assertTrue(result.isErr, "Invalid stored component must reject the complete statement, not be omitted")
        assertIs<ServerError>(result.error)
    }

    @Test
    fun ownedChildUsesChildKeysAndPreservesAllMetadataPolicyAndTypedConstraints() = runTest {
        metadataQueries.create(ownAccount, ownChild, "openid_provider", METADATA).executeAsOne()
        metadataQueries.create(ownAccount, ownChild, "federation_entity", """{"organization_name":"Child"}""").executeAsOne()
        metadataQueries.create(foreignAccount, foreignChild, "openid_provider", """{"foreign":true}""").executeAsOne()
        constraintQueries.create(ownAccount, ownChild, CONSTRAINTS).executeAsOne()
        policyQueries.create(ownAccount, "openid_provider", POLICY).executeAsOne()
        policyQueries.create(ownAccount, "federation_entity", SECOND_POLICY).executeAsOne()
        policyQueries.create(foreignAccount, "openid_provider", """{"foreign":{"value":true}}""").executeAsOne()

        val result = observe()
        assertTrue(result.isOk)
        val statement = result.value
        assertEquals(SUPERIOR, statement.iss)
        assertEquals(CHILD, statement.sub)
        val childKeys = assertNotNull(statement.jwks.propertyKeys)
        assertEquals(setOf(
            Jwk(kty = "RSA", kid = "child-one", n = "AQID", e = "AQAB", alg = "RS256", use = "sig"),
            Jwk(kty = "RSA", kid = "child-two", n = "BAUG", e = "AQAB", alg = "RS256", use = "sig"),
        ), childKeys.toSet())
        assertEquals(2, childKeys.size)
        assertEquals(Json.parseToJsonElement(
            """{"openid_provider":{"issuer":"https://child.example/Case/%2F","x_unknown":{"null":null,"ordered":[2,1],"typed":true}},"federation_entity":{"organization_name":"Child"}}"""
        ).jsonObject, statement.metadata)
        assertEquals(Json.parseToJsonElement(
            """{"openid_provider":{"issuer":{"value":"https://child.example/Case/%2F","x_operator":{"nested":[null,"literal",7]}}},"federation_entity":{"organization_name":{"default":"Child operator","x_operator":false}}}"""
        ).jsonObject, statement.metadataPolicy)
        assertEquals(2, statement.constraints?.maxPathLength)
        assertEquals(listOf("openid_credential_issuer", "openid_credential_verifier"),
            statement.constraints?.allowedEntityTypes)
        assertEquals(listOf(ownAccount), resolverCalls)
        assertEquals(setOf("Subordinate", "SubordinateJwk", "SubordinateMetadata", "SubordinateConstraint", "MetadataPolicy"),
            reads.tables.toSet())
    }

    @Test
    fun configuredLifetimeBoundsExpiryAndAbsentLifetimeIssuesNothing() = runTest {
        val statement = observe().value
        assertEquals(86_400L, (statement.exp - statement.iat).toLong())

        val unconfigured = GetSubordinateStatementCommandImpl(
            execution, resolver, subordinateQueries, keyQueries, metadataQueries, constraintQueries, policyQueries,
            configuredLifetime(null),
        ).execute(GetSubordinateStatementArgs(ownAccount, ownChild))
        assertTrue(unconfigured.isErr, "Without a configured lifetime no statement is issued")
        assertIs<ServerError>(unconfigured.error)
    }

    @Test
    fun absentOptionalConstraintsMetadataAndPoliciesRemainAbsent() = runTest {
        val result = observe()
        assertTrue(result.isOk)
        assertNull(result.value.constraints)
        assertNull(result.value.metadata)
        assertNull(result.value.metadataPolicy)
        assertEquals(CHILD, result.value.sub)
    }

    @Test fun foreignChildRejectsBeforeDownstreamReadsOrIssuerResolution() = runTest {
        assertNotFoundWithoutDownstream(foreignChild)
    }

    @Test fun foreignPoisonedKeyCannotChangeNotFoundIntoServerError() = runTest {
        val foreignKey = keyQueries.findBySubordinateId(foreignChild).executeAsOne()
        keyQueries.delete(foreignKey.id).executeAsOne()
        keyQueries.create(foreignChild, "{broken-foreign-key").executeAsOne()
        assertNotFoundWithoutDownstream(foreignChild)
    }

    @Test fun missingChildRejectsBeforeDownstreamReadsOrIssuerResolution() = runTest {
        assertNotFoundWithoutDownstream(UUID.randomUUID().toString())
    }

    @Test fun deletedChildRejectsBeforeDownstreamReadsOrIssuerResolution() = runTest {
        subordinateQueries.delete(ownChild).executeAsOne()
        assertNotFoundWithoutDownstream(ownChild)
    }

    @Test fun malformedConstraintsMustNotSilentlyBecomeAbsent() = runTest {
        constraintQueries.create(ownAccount, ownChild, "{broken").executeAsOne()
        assertServerError()
    }

    @Test fun arrayConstraintsMustNotSilentlyBecomeAbsent() = runTest {
        constraintQueries.create(ownAccount, ownChild, "[]").executeAsOne()
        assertServerError()
    }

    @Test fun wrongTypedConstraintsMustNotSilentlyBecomeAbsent() = runTest {
        constraintQueries.create(ownAccount, ownChild, """{"max_path_length":"two"}""").executeAsOne()
        assertServerError()
    }

    @Test fun outOfRangeConstraintsAreRefusedNotPublished() = runTest {
        constraintQueries.create(ownAccount, ownChild, """{"max_path_length":-1}""").executeAsOne()
        assertServerError()
    }

    @Test fun policyWithWrongOperatorTypeIsRefusedNotPublished() = runTest {
        policyQueries.create(ownAccount, "openid_provider", """{"grant_types":{"subset_of":"authorization_code"}}""").executeAsOne()
        assertServerError()
    }

    @Test fun childWithoutPublicKeysIsNotVouchedFor() = runTest {
        keyQueries.findBySubordinateId(ownChild).executeAsList().forEach { keyQueries.delete(it.id).executeAsOne() }
        val result = observe()
        assertTrue(result.isErr, "A statement without the child's keys must not be issued")
        assertIs<InvalidRequestError>(result.error)
    }

    @Test fun malformedPolicyMustNotSilentlyBecomeAbsent() = runTest {
        policyQueries.create(ownAccount, "openid_provider", "{broken").executeAsOne()
        assertServerError()
    }

    @Test fun arrayPolicyMustNotSilentlyBecomeAbsent() = runTest {
        policyQueries.create(ownAccount, "openid_provider", "[]").executeAsOne()
        assertServerError()
    }

    @Test fun primitivePolicyMustNotSilentlyBecomeAbsent() = runTest {
        policyQueries.create(ownAccount, "openid_provider", "true").executeAsOne()
        assertServerError()
    }

    @Test fun oneInvalidPolicyMustRejectRatherThanReturnPartialValidPolicy() = runTest {
        policyQueries.create(ownAccount, "openid_provider", POLICY).executeAsOne()
        policyQueries.create(ownAccount, "federation_entity", "{broken").executeAsOne()
        assertServerError()
    }

    @Test fun malformedMetadataAlreadyRejectsCompleteStatement() = runTest {
        metadataQueries.create(ownAccount, ownChild, "openid_provider", "{broken").executeAsOne()
        assertServerError()
    }

    @Test fun nonObjectMetadataAlreadyRejectsCompleteStatement() = runTest {
        metadataQueries.create(ownAccount, ownChild, "openid_provider", "[]").executeAsOne()
        assertServerError()
    }

    @Test fun initialStorageFailureUsesControlledServerErrorBoundary() = runTest {
        val sentinel = SQLException("owned initial-read failure", "XX000")
        reads.failTable = "Subordinate"
        reads.failure = sentinel
        val attempt = runCatching { observe() }
        assertTrue(attempt.isSuccess, "Initial storage read must adapt ordinary failure rather than throw")
        val result = attempt.getOrThrow()
        assertTrue(result.isErr)
        assertSame(sentinel, assertIs<ServerError>(result.error).exception)
        assertEquals(emptyList(), resolverCalls)
        assertEquals(listOf("Subordinate"), reads.tables)
    }

    private suspend fun assertReadCancellation(table: String) {
        val sentinel = TestCancellationSentinel("owned cancellation at " + table)
        reads.failTable = table
        reads.failure = sentinel
        val thrown = runCatching { observe() }.exceptionOrNull()
        assertSame(sentinel, thrown, "Storage cancellation must propagate by identity, not become ServerError")
        assertEquals(table, reads.tables.last())
    }

    @Test fun initialStorageCancellationPropagatesByIdentity() = runTest {
        assertReadCancellation("Subordinate")
    }

    @Test fun childKeyReadCancellationPropagatesByIdentity() = runTest {
        assertReadCancellation("SubordinateJwk")
    }

    @Test fun childMetadataReadCancellationPropagatesByIdentity() = runTest {
        assertReadCancellation("SubordinateMetadata")
    }

    @Test fun childConstraintReadCancellationPropagatesByIdentity() = runTest {
        assertReadCancellation("SubordinateConstraint")
    }

    @Test fun accountPolicyReadCancellationPropagatesByIdentity() = runTest {
        assertReadCancellation("MetadataPolicy")
    }

    @Test fun identifierResolverCancellationPropagatesByIdentity() = runTest {
        val sentinel = TestCancellationSentinel("owned identifier-resolution cancellation")
        resolverFailure = sentinel
        val thrown = runCatching { observe() }.exceptionOrNull()
        assertSame(sentinel, thrown, "Resolver cancellation must propagate by identity")
        assertEquals(listOf(ownAccount), resolverCalls)
    }

    @Test fun ordinaryIdentifierResolverFailureRetainsControlledErrorAndCause() = runTest {
        val sentinel = IllegalStateException("owned identifier-resolution failure")
        resolverFailure = sentinel
        val result = observe()
        assertTrue(result.isErr)
        assertSame(sentinel, assertIs<ServerError>(result.error).exception)
        assertEquals(listOf(ownAccount), resolverCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class TestCancellationSentinel(message: String) :
        CancellationException(message), CopyableThrowable<TestCancellationSentinel> {
        override fun createCopy(): TestCancellationSentinel? = null
    }

    /** Observe real prepareStatement calls; return the original concrete statement to SQLDelight. */
    private class ReadRecordingDataSource(private val delegate: DataSource) : DataSource by delegate {
        var enabled = false
        var failTable: String? = null
        var failure: Throwable? = null
        val sql = mutableListOf<String>()
        val tables = mutableListOf<String>()
        private val tablePattern = Regex("(?i)\\bFROM\\s+(SubordinateConstraint|SubordinateMetadata|SubordinateJwk|Subordinate|MetadataPolicy)\\b")

        override fun getConnection(): Connection = record(delegate.connection)
        override fun getConnection(username: String?, password: String?): Connection =
            record(delegate.getConnection(username, password))

        private fun record(connection: Connection): Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader, arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (enabled && method.name == "prepareStatement" && args?.firstOrNull() is String) {
                val query = args[0] as String
                sql += query
                val table = tablePattern.find(query)?.groupValues?.get(1)
                table?.let { tables += it }
                if (table != null && table == failTable) failure?.let { throw it }
            }
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw requireNotNull(e.cause)
            }
        } as Connection
    }
}
