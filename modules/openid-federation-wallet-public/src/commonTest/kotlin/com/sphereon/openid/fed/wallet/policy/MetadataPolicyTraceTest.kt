package com.sphereon.openid.fed.wallet.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataPolicyTraceTest {
    private val subject = obj(
        """{"iss":"https://subject.example","sub":"https://subject.example","metadata":{"federation_entity":{"organization_name":"Subject"},"oauth_client":{"client_name":"subject","scope":"openid profile","redirect_uris":["https://subject.example/cb"],"remove":"old","unchanged":"same"},"openid_provider":{"issuer":"https://subject.example"}}}"""
    )

    @Test
    fun tracedResolutionMatchesFullLegacyResolutionThenEntityTypeFiltering() {
        // Cover the same absent-role, scope, constraint, validation and critical cases as
        // the existing policy vectors, including failed resolutions' retained metadata.
        val chains = listOf(
            emptyList(),
            listOf(subject),
            listOf(obj("""{"iss":"https://subject.example","sub":"https://subject.example"}"""), statement()),
            listOf(subject, statement(metadata = """{"oauth_client":{"client_name":"override"},"inactive":{"parameter":"ignored"}}""")),
            listOf(subject, statement(policy = """{"oauth_client":{"scope":{"add":["email"],"subset_of":["openid","email"]},"remove":{"value":null},"new":{"default":"created"}},"inactive":{"parameter":{"essential":true}}}""")),
            listOf(subject, statement(constraints = """{"allowed_entity_types":[]}""", policy = """{"openid_provider":{"issuer":{"value":"https://policy.example"}}}""")),
            listOf(subject, statement(constraints = """{"allowed_entity_types":null}""")),
            listOf(subject, statement(policy = """{"oauth_client":{"missing":{"essential":true}}}""")),
            listOf(subject, statement(policy = """{"inactive":{"parameter":{"add":null}}}""")),
            listOf(subject, statement(policy = """{"inactive":{"parameter":"invalid"}}""")),
            listOf(subject, statement(policy = """{"oauth_client":{"client_name":{"extension":"ignored"}}}""")),
            listOf(subject, statement(critical = """["extension"]""")),
            listOf(subject, statement(critical = """["value"]""")),
            listOf(subject, statement(critical = "[]")),
            listOf(
                subject,
                statement(policy = """{"oauth_client":{"client_name":{"value":"immediate"}}}"""),
                statement("https://anchor.example", "https://intermediate.example", policy = """{"oauth_client":{"client_name":{"value":"anchor"}}}""")
            )
        )
        for (chain in chains) {
            val legacy = MetadataPolicyOperators.resolveFromTrustChainPayloads(chain)
            for (filter in listOf(null, emptyList(), listOf("oauth_client"), listOf("inactive"))) {
                val traced = MetadataPolicyOperators.resolveWithTrace(chain, filter)
                assertEquals(MetadataPolicyOperators.filterEntityTypes(legacy.metadata, filter), traced.metadata)
                assertEquals(legacy.isValid, traced.isValid)
                assertEquals(legacy.errors, traced.errors.map { it.message })
                assertEquals(legacy.warnings, traced.warnings)
            }
        }
    }

    @Test
    fun contributionsKeepOriginalIndexesAndIssuerInAnchorDownOrder() {
        val immediate = statement(
            metadata = """{"oauth_client":{"client_name":"immediate"}}""",
            policy = """{"oauth_client":{"client_name":{"default":"fallback"},"new":{"default":"created"},"remove":{"value":null}}}"""
        )
        val upper = statement(
            "https://anchor.example", "https://intermediate.example",
            metadata = """{"oauth_client":{"client_name":"upper metadata is not an override","upper_only":"ignored"}}""",
            policy = """{"oauth_client":{"client_name":{"default":"fallback"}}}"""
        )
        val anchor = obj(
            """{"iss":"https://anchor.example","sub":"https://anchor.example","metadata":{"oauth_client":{"client_name":"anchor metadata is not an override"}}}"""
        )

        val result = MetadataPolicyOperators.resolveWithTrace(listOf(subject, immediate, upper, anchor))
        val name = result.entry("oauth_client", "client_name")

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(listOf(3, 2, 2, 1, 1, 0), name.contributions.map { it.statementIndex })
        assertEquals(
            listOf("https://anchor.example", "https://anchor.example", "https://anchor.example", "https://intermediate.example", "https://intermediate.example", "https://subject.example"),
            name.contributions.map { it.issuer }
        )
        assertEquals(
            listOf(PolicyContributionSource.METADATA, PolicyContributionSource.METADATA, PolicyContributionSource.METADATA_POLICY, PolicyContributionSource.METADATA, PolicyContributionSource.METADATA_POLICY, PolicyContributionSource.METADATA),
            name.contributions.map { it.source }
        )
        assertEquals(JsonPrimitive("subject"), name.valueBefore)
        assertEquals(JsonPrimitive("immediate"), name.valueAfter)
        assertEquals(obj("""{"default":"fallback"}"""), name.mergedOperators)
        assertEquals(PolicyTraceOutcome.MODIFIED, name.outcome)
        assertEquals(PolicyTraceOutcome.SET, result.entry("oauth_client", "new").outcome)
        assertEquals(PolicyTraceOutcome.UNCHANGED, result.entry("oauth_client", "unchanged").outcome)
        assertEquals(PolicyTraceOutcome.UNCHANGED, result.entry("oauth_client", "upper_only").outcome)
        assertNull(result.entry("oauth_client", "upper_only").valueAfter)
        val removed = result.entry("oauth_client", "remove")
        assertEquals(PolicyTraceOutcome.REMOVED, removed.outcome)
        assertNull(removed.valueAfter)
        assertEquals(JsonNull, removed.contributions.first().operators?.get("value"))
    }

    @Test
    fun entityTypeFilterDoesNotTurnResolvedValuesIntoPolicyRemovals() {
        val result = MetadataPolicyOperators.resolveWithTrace(listOf(subject), listOf("oauth_client"))

        assertEquals(setOf("oauth_client"), result.metadata.keys)
        val otherRole = result.entry("openid_provider", "issuer")
        assertEquals(PolicyTraceOutcome.UNCHANGED, otherRole.outcome)
        assertEquals(JsonPrimitive("https://subject.example"), otherRole.valueAfter)
    }

    @Test
    fun signedAllowedTypesRemovalAppearsInTraceAndInactivePolicyCannotRestoreIt() {
        val superior = statement(
            constraints = """{"allowed_entity_types":[]}""",
            policy = """{"openid_provider":{"issuer":{"value":"https://policy.example"}},"inactive":{"new":{"default":"ignored"}}}"""
        )

        val result = MetadataPolicyOperators.resolveWithTrace(listOf(subject, superior))

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(PolicyTraceOutcome.REMOVED, result.entry("openid_provider", "issuer").outcome)
        assertNull(result.entry("openid_provider", "issuer").valueAfter)
        assertEquals(PolicyTraceOutcome.UNCHANGED, result.entry("inactive", "new").outcome)
        assertNull(result.entry("inactive", "new").valueAfter)
    }

    @Test
    fun conflictIsCategorizedAtMergingAndNamesTheParameter() {
        val immediate = statement(policy = """{"oauth_client":{"client_name":{"value":"immediate"}}}""")
        val upper = statement("https://anchor.example", "https://intermediate.example", policy = """{"oauth_client":{"client_name":{"value":"anchor"}}}""")

        val result = MetadataPolicyOperators.resolveWithTrace(listOf(subject, immediate, upper))

        assertFalse(result.isValid)
        assertEquals(PolicyResolutionErrorCode.POLICY_CONFLICT, result.errors.single().code)
        assertEquals("oauth_client", result.errors.single().entityType)
        assertEquals("client_name", result.errors.single().parameter)
        val entry = result.entry("oauth_client", "client_name")
        assertEquals(PolicyTraceOutcome.ERROR, entry.outcome)
        assertNull(entry.mergedOperators)
        assertEquals(result.errors.single().message, entry.message)
        assertEquals(JsonPrimitive("subject"), entry.valueAfter)
    }

    @Test
    fun mergedOperatorCombinationConflictUsesStructuralParameterLocation() {
        val immediate = statement(policy = """{"oauth_client":{"redirect_uris":{"add":["https://extra.example/cb"]}}}""")
        val upper = statement("https://anchor.example", "https://intermediate.example", policy = """{"oauth_client":{"redirect_uris":{"value":["https://subject.example/cb"]}}}""")

        val result = MetadataPolicyOperators.resolveWithTrace(listOf(subject, immediate, upper))

        assertFalse(result.isValid)
        assertEquals(PolicyResolutionErrorCode.POLICY_CONFLICT, result.errors.single().code)
        assertEquals("redirect_uris", result.errors.single().parameter)
        assertEquals(PolicyTraceOutcome.ERROR, result.entry("oauth_client", "redirect_uris").outcome)
    }

    @Test
    fun combinedPolicyValidationKeepsSuperiorParameterOrderForConflictLocation() {
        val immediate = statement(policy = """{"oauth_client":{"second":{"add":["extra"]},"first":{"add":["extra"]}}}""")
        val upper = statement("https://anchor.example", "https://intermediate.example", policy = """{"oauth_client":{"first":{"value":["first"]},"second":{"value":["second"]}}}""")

        val result = MetadataPolicyOperators.resolveWithTrace(listOf(subject, immediate, upper))

        assertFalse(result.isValid)
        assertEquals("first", result.errors.first().parameter)
        assertEquals(PolicyTraceOutcome.ERROR, result.entry("oauth_client", "first").outcome)
        // The trace reports every conflicting parameter; the plain resolver stops at the first one, which must agree.
        assertEquals(
            MetadataPolicyOperators.resolveFromTrustChainPayloads(listOf(subject, immediate, upper)).errors.first(),
            result.errors.first().message,
        )
    }

    @Test
    fun applicationViolationsAreAttributedToEachParameter() {
        val superior = statement(
            policy = """{"oauth_client":{"client_name":{"one_of":["other"]},"missing":{"essential":true},"scope":{"superset_of":["email"]}}}"""
        )

        val result = MetadataPolicyOperators.resolveWithTrace(listOf(subject, superior))

        assertFalse(result.isValid)
        assertEquals(listOf("client_name", "missing", "scope"), result.errors.map { it.parameter })
        assertTrue(result.errors.all { it.code == PolicyResolutionErrorCode.POLICY_VIOLATION })
        for (parameter in listOf("client_name", "missing", "scope")) {
            assertEquals(PolicyTraceOutcome.ERROR, result.entry("oauth_client", parameter).outcome)
        }
        assertEquals(PolicyTraceOutcome.UNCHANGED, result.entry("oauth_client", "unchanged").outcome)
    }

    @Test
    fun unknownCriticalOperatorFailsForInactiveRoleAndUnusedDeclaration() {
        val used = MetadataPolicyOperators.resolveWithTrace(listOf(
            subject,
            statement(policy = """{"inactive":{"parameter":{"extension":"ignored"}}}""", critical = """["extension"]""")
        ))
        val unused = MetadataPolicyOperators.resolveWithTrace(listOf(subject, statement(critical = """["extension"]""")))

        assertFalse(used.isValid)
        assertFalse(unused.isValid)
        assertEquals(PolicyResolutionErrorCode.UNKNOWN_CRITICAL_OPERATOR, used.errors.single().code)
        assertEquals(PolicyResolutionErrorCode.UNKNOWN_CRITICAL_OPERATOR, unused.errors.single().code)
        assertEquals(PolicyTraceOutcome.ERROR, used.entry("inactive", "parameter").outcome)
        assertTrue(unused.trace.all { it.outcome == PolicyTraceOutcome.ERROR })
    }

    @Test
    fun unknownNoncriticalOperatorsAreContributionsWithoutMergeSemantics() {
        val immediate = statement(policy = """{"oauth_client":{"client_name":{"extension":"immediate"}}}""")
        val upper = statement("https://anchor.example", "https://intermediate.example", policy = """{"oauth_client":{"client_name":{"extension":"anchor"}}}""")

        val result = MetadataPolicyOperators.resolveWithTrace(listOf(subject, immediate, upper))

        assertTrue(result.isValid, result.errors.toString())
        val entry = result.entry("oauth_client", "client_name")
        assertEquals(PolicyTraceOutcome.UNCHANGED, entry.outcome)
        assertNull(entry.mergedOperators)
        assertEquals(JsonPrimitive("anchor"), entry.contributions.first().operators?.get("extension"))
    }

    @Test
    fun traceModelsAreSerializableWithOriginalNullRemovalOperator() {
        val result = MetadataPolicyOperators.resolveWithTrace(listOf(subject, statement(policy = """{"oauth_client":{"remove":{"value":null}}}""")))

        val encoded = Json.encodeToString(TracedPolicyResolution.serializer(), result)
        val decoded = Json.decodeFromString(TracedPolicyResolution.serializer(), encoded)

        assertEquals(result, decoded)
        assertEquals(JsonNull, decoded.entry("oauth_client", "remove").mergedOperators?.get("value"))
    }

    @Test
    fun conflictsDoNotHideOtherParameterFailures() {
        val immediate = statement(policy = """{"oauth_client":{"first":{"value":"lower"},"second":{"value":"lower"}}}""")
        val upper = statement("https://anchor.example", "https://intermediate.example",
            policy = """{"oauth_client":{"first":{"value":"upper"},"second":{"value":"upper"},"missing":{"essential":true}}}""")
        val chain = listOf(subject, immediate, upper)
        val result = MetadataPolicyOperators.resolveWithTrace(chain)

        assertEquals(MetadataPolicyOperators.resolveFromTrustChainPayloads(chain).metadata, result.metadata)
        assertEquals(setOf("first", "second", "missing"), result.errors.map { it.parameter }.toSet())
        assertTrue(result.errors.any { it.code == PolicyResolutionErrorCode.POLICY_VIOLATION })
        for (name in listOf("first", "second", "missing")) assertEquals(PolicyTraceOutcome.ERROR, result.entry("oauth_client", name).outcome)
    }

    @Test
    fun malformedMetadataReturnsTraceDiagnostics() {
        val malformed = obj("""{"iss":"https://subject.example","sub":"https://subject.example","metadata":{"oauth_client":[]}}""")
        val result = MetadataPolicyOperators.resolveWithTrace(listOf(malformed))

        assertFalse(result.isValid)
        assertEquals(PolicyResolutionErrorCode.POLICY_VIOLATION, result.errors.single().code)
    }

    private fun TracedPolicyResolution.entry(entityType: String, parameter: String): PolicyTraceEntry =
        trace.single { it.entityType == entityType && it.parameter == parameter }

    private fun statement(
        issuer: String = "https://intermediate.example",
        subject: String = "https://subject.example",
        metadata: String? = null,
        policy: String? = null,
        critical: String? = null,
        constraints: String? = null
    ): JsonObject = obj(buildString {
        append("{\"iss\":\"$issuer\",\"sub\":\"$subject\"")
        metadata?.let { append(",\"metadata\":$it") }
        policy?.let { append(",\"metadata_policy\":$it") }
        critical?.let { append(",\"metadata_policy_crit\":$it") }
        constraints?.let { append(",\"constraints\":$it") }
        append('}')
    })

    private fun obj(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
