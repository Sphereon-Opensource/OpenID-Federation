/*
 * Copyright 2025 Sphereon International B.V.
 *
 * Licensed under the Apache License, Version 2.0
 */

package com.sphereon.openid.fed.trust

import com.sphereon.openid.fed.client.command.trustChain.TrustAnchorKeyResolver
import com.sphereon.core.api.IdkOkResult
import com.sphereon.core.api.IdkResult
import com.sphereon.core.api.Ok
import com.sphereon.core.api.cache.CacheBackend
import com.sphereon.core.api.cache.CacheManager
import com.sphereon.core.api.cache.CacheRequirements
import com.sphereon.core.api.cache.CacheSerializer
import com.sphereon.core.api.cache.CacheStatistics
import com.sphereon.core.api.cache.ScopedCache
import com.sphereon.core.api.cache.ScopedKey
import com.sphereon.core.api.conf.AppConfigService
import com.sphereon.core.api.conf.ConfigLevel
import com.sphereon.core.api.conf.ConfigService
import com.sphereon.core.api.conf.PrincipalConfigService
import com.sphereon.core.api.conf.TenantConfigService
import com.sphereon.core.api.context.ContextConfig
import com.sphereon.core.api.context.IdkScope
import com.sphereon.core.api.context.SessionExecution
import com.sphereon.core.api.log.AsyncLogService
import com.sphereon.core.api.log.LogMessage
import com.sphereon.core.api.log.LoggerConfig
import com.sphereon.core.api.log.SessionLogManager
import com.sphereon.core.api.log.SessionLogService
import com.sphereon.di.context.NoOpSessionContext
import com.sphereon.di.context.createAnonymousSessionContext
import com.sphereon.di.session.SessionContext
import com.sphereon.di.session.SessionContextManager
import com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationCommand
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainArgs
import com.sphereon.trust.core.model.TrustAnchor
import com.sphereon.openid.fed.client.command.trustChain.ResolveTrustChainCommand
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainArgs
import com.sphereon.openid.fed.client.command.trustChain.VerifyTrustChainCommand
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkArgs
import com.sphereon.openid.fed.client.command.trustMark.VerifyTrustMarkCommand
import com.sphereon.openid.fed.core.error.FederationError
import com.sphereon.openid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.openid.fed.openapi.models.TrustChainResolveResponse
import com.sphereon.openid.fed.openapi.models.TrustMarkValidationResponse
import com.sphereon.openid.fed.openapi.models.TrustMark
import com.sphereon.openid.fed.openapi.models.VerifyTrustChainResponse
import com.sphereon.trust.core.config.TrustConfig
import com.sphereon.trust.core.config.TrustConfigProvider
import com.sphereon.trust.core.config.OidfTrustConfig
import com.sphereon.trust.core.config.TrustAnchorsConfig
import com.sphereon.trust.core.model.TrustChainHopPosition
import com.sphereon.trust.core.model.TrustChainLinks
import com.sphereon.trust.core.model.TrustContext
import com.sphereon.trust.core.model.EntityDiscoveryOptions
import com.sphereon.trust.core.model.TrustStatus
import com.sphereon.trust.core.model.TrustValidationRequest
import com.sphereon.trust.core.model.TrustValidationResult
import com.sphereon.crypto.resolution.extern.ExternalIdentifierOIDFEntityIdOpts
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class OidfTrustValidationServiceTest {

    @Test
    fun validatesSuccessfulTrustChain() = runTest {
        val leaf = "https://entity.example.com"
        val anchor = "https://anchor.example.com"
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf(
                    entityStatementJwt(leaf, leaf),
                    entityStatementJwt(leaf, anchor),
                    entityStatementJwt(anchor, anchor),
                )
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = true))
        )

        val result = service.validate(createRequest(
            entityIdentifier = leaf,
            trustAnchors = anchor
        ))

        assertTrue(result.trusted)
        assertEquals(TrustStatus.TRUSTED, result.status)
        assertEquals(3, result.validationPath.size)
        assertNotNull(result.trustChain)
    }

    @Test
    fun aChainWhoseMetadataPoliciesDoNotResolveIsNotTrusted() = runTest {
        // OpenID Federation 1.1 §10.2: metadata is resolved after validation; a policy error invalidates the chain.
        val leaf = "https://rp.example.com"
        val anchor = "https://anchor.example.com"
        val now = Clock.System.now().epochSeconds
        val keys = """{"keys":[{"kty":"EC","kid":"key-1","crv":"P-256","x":"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU","y":"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"}]}"""
        val leafStatement = compactTestJwt("entity-statement+jwt", """{"iss":"$leaf","sub":"$leaf","iat":${now - 60},"exp":${now + 3600},
            "jwks":$keys,"metadata":{"openid_relying_party":{"token_endpoint_auth_method":"client_secret_basic"}}}""")
        val anchorAboutLeaf = compactTestJwt("entity-statement+jwt", """{"iss":"$anchor","sub":"$leaf","iat":${now - 60},"exp":${now + 3600},
            "jwks":$keys,"metadata_policy":{"openid_relying_party":{"token_endpoint_auth_method":{"one_of":["private_key_jwt"]}}}}""")
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(leafStatement, anchorAboutLeaf, entityStatementJwt(anchor, anchor)))),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = true)),
        )

        val result = service.validate(createRequest(entityIdentifier = leaf, trustAnchors = anchor))

        assertEquals(TrustStatus.UNTRUSTED, result.status)
        assertTrue(result.details?.contains("metadata") == true, result.details)
    }

    @Test
    fun aFederationChainArrivesOrderedFromLeafToAnchor() = runTest {
        val leaf = "https://leaf.example"
        val intermediate = "https://intermediate.example"
        val anchor = "https://anchor.example"
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf(
                    entityStatementJwt(sub = leaf, iss = intermediate),
                    entityStatementJwt(sub = intermediate, iss = anchor),
                    entityStatementJwt(sub = anchor, iss = anchor),
                )
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = true))
        )

        val result = service.validate(createRequest(
            entityIdentifier = leaf,
            trustAnchors = anchor
        ))

        val chain = result.trustChain!!
        assertEquals(3, chain.hops.size)
        assertEquals(TrustChainHopPosition.LEAF, chain.hops.first().position)
        assertEquals(TrustChainHopPosition.ANCHOR, chain.hops.last().position)
        assertEquals(listOf(leaf, intermediate, anchor), chain.hops.map { it.identifier })
        assertEquals(TrustChainLinks.VERIFIED, chain.links)
        assertNull(result.attestationAuthorisation)
        assertNull(result.domainAdmission)
    }

    @Test
    fun aMechanismThatResolvesNoChainLeavesItAbsentNotEmptyButPresent() = runTest {
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = emptyList(),
                errorMessage = "No path to trust anchor"
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = false))
        )

        val result = service.validate(createRequest(
            entityIdentifier = "https://untrusted.example.com",
            trustAnchors = "https://anchor.example.com"
        ))

        assertNull(result.trustChain)
    }

    @Test
    fun aBrokenFederationLinkKeepsTheOrderedHops() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf(
                    entityStatementJwt(sub = leaf, iss = anchor),
                    entityStatementJwt(sub = anchor, iss = anchor),
                )
            )),
            verifyResult = Ok(VerifyTrustChainResponse(
                isValid = false,
                errorMessage = "Signature verification failed"
            ))
        )

        val result = service.validate(createRequest(
            entityIdentifier = leaf,
            trustAnchors = anchor
        ))

        assertFalse(result.trusted)
        assertEquals(TrustChainLinks.BROKEN, result.trustChain!!.links)
        assertEquals(listOf(leaf, anchor), result.trustChain!!.hops.map { it.identifier })
    }

    @Test
    fun returnsUntrustedWhenChainResolutionFails() = runTest {
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = emptyList(),
                errorMessage = "No path to trust anchor"
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = false))
        )

        val result = service.validate(createRequest(
            entityIdentifier = "https://untrusted.example.com",
            trustAnchors = "https://anchor.example.com"
        ))

        assertFalse(result.trusted)
        assertEquals(TrustStatus.UNTRUSTED, result.status)
        assertTrue(result.details?.contains("No path to trust anchor") == true)
    }

    @Test
    fun returnsUntrustedWhenChainVerificationFails() = runTest {
        val leaf = "https://entity.example.com"
        val anchor = "https://anchor.example.com"
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf(entityStatementJwt(leaf, leaf), entityStatementJwt(leaf, anchor))
            )),
            verifyResult = Ok(VerifyTrustChainResponse(
                isValid = false,
                errorMessage = "Signature verification failed"
            ))
        )

        val result = service.validate(createRequest(
            entityIdentifier = leaf,
            trustAnchors = anchor
        ))

        assertFalse(result.trusted)
        assertEquals(TrustStatus.UNTRUSTED, result.status)
        assertTrue(result.details?.contains("Signature verification failed") == true)
    }

    @Test
    fun returnsErrorWhenNoEntityIdentifier() = runTest {
        val service = createService()

        val result = service.validate(TrustValidationRequest(
            identifier = ExternalIdentifierOIDFEntityIdOpts(identifier = "test"),
            context = TrustContext(type = TrustContext.TYPE_OPENID_FEDERATION)
        ))

        assertEquals(TrustStatus.VALIDATION_ERROR, result.status)
        assertTrue(result.details?.contains("No entityIdentifier") == true)
    }

    @Test
    fun returnsErrorWhenNoTrustAnchors() = runTest {
        val service = createService(
            configTrustAnchors = emptyList()
        )

        val result = service.validate(createRequest(
            entityIdentifier = "https://entity.example.com"
            // no trustAnchors in params, and config has empty list
        ))

        assertEquals(TrustStatus.VALIDATION_ERROR, result.status)
        assertTrue(result.details?.contains("No trust anchors") == true)
    }

    @Test
    fun usesConfigTrustAnchorsWhenNotInRequest() = runTest {
        var capturedAnchors: Array<String>? = null
        val leaf = "https://entity.example.com"
        val anchor = "https://config-anchor.example.com"
        val service = createService(
            configTrustAnchors = listOf(anchor),
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf(entityStatementJwt(leaf, leaf), entityStatementJwt(leaf, anchor))
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = true)),
            onResolve = { args -> capturedAnchors = args.trustAnchors }
        )

        service.validate(createRequest(
            entityIdentifier = leaf
            // no trustAnchors param — should use config
        ))

        assertEquals("https://config-anchor.example.com", capturedAnchors?.firstOrNull())
    }

    @Test
    fun requestParamsOverrideConfig() = runTest {
        var capturedAnchors: Array<String>? = null
        val leaf = "https://entity.example.com"
        val anchor = "https://override-anchor.example.com"
        val service = createService(
            configTrustAnchors = listOf("https://config-anchor.example.com"),
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf(entityStatementJwt(leaf, leaf), entityStatementJwt(leaf, anchor))
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = true)),
            onResolve = { args -> capturedAnchors = args.trustAnchors }
        )

        service.validate(createRequest(
            entityIdentifier = leaf,
            trustAnchors = anchor
        ))

        assertEquals("https://override-anchor.example.com", capturedAnchors?.firstOrNull())
    }

    @Test
    fun selectedAnchorIsTheChainAnchorNotTheFirstConfigured() = runTest {
        val taA = "https://ta-a.example.com"
        val taB = "https://ta-b.example.com"
        val leaf = "https://leaf.example.com"
        var capturedAnchor: String? = "unset"
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf(
                    entityStatementJwt(sub = leaf, iss = taB),
                    entityStatementJwt(sub = taB, iss = taB),
                )
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = true)),
            onVerify = { capturedAnchor = it.trustAnchor },
        )

        val result = service.validate(createRequest(
            entityIdentifier = leaf,
            trustAnchors = "$taA,$taB",
        ))

        assertTrue(result.trusted)
        assertEquals(taB, capturedAnchor)
        assertEquals(taB, service.selectedTrustAnchor(
            listOf(entityStatementJwt(sub = leaf, iss = taB), entityStatementJwt(sub = taB, iss = taB)),
            listOf(taA, taB),
        ))
    }

    @Test
    fun selectsTerminalSuperiorIssuerFromFullStatements() = runTest {
        val leaf = "https://leaf.example"
        val intermediate = "https://intermediate.example"
        val firstAnchor = "https://first-anchor.example"
        val terminalAnchor = "https://terminal-anchor.example"
        var verifiedAt: String? = null
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf),
                entityStatementJwt(leaf, intermediate),
                entityStatementJwt(intermediate, terminalAnchor),
            ))),
            onVerify = { verifiedAt = it.trustAnchor },
        )

        val result = service.validate(createRequest(leaf, "$firstAnchor,$terminalAnchor"))

        assertTrue(result.trusted)
        assertEquals(terminalAnchor, verifiedAt)
    }

    @Test
    fun rejectsChainWhoseTerminalIssuerIsNotConfiguredEvenWhenEarlierHopIs() = runTest {
        val leaf = "https://leaf.example"
        val requestedAnchor = "https://requested-anchor.example"
        val otherSuperior = "https://other-superior.example"
        var verifyCalls = 0
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf),
                entityStatementJwt(leaf, requestedAnchor),
                entityStatementJwt(requestedAnchor, otherSuperior),
            ))),
            onVerify = { verifyCalls++ },
        )

        val result = service.validate(createRequest(leaf, requestedAnchor))

        assertFalse(result.trusted)
        assertEquals(0, verifyCalls)
    }

    @Test
    fun rejectsMalformedTerminalWithoutSingleAnchorFallback() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val service = createService(resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
            entityStatementJwt(leaf, leaf),
            entityStatementJwt(leaf, anchor),
            "not-a-compact-jwt",
        ))))

        assertFalse(service.validate(createRequest(leaf, anchor)).trusted)
    }

    @Test
    fun acceptedChainExpiresAtEarliestSignedStatementExpiry() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val earliestExpiry = Clock.System.now().epochSeconds + 600
        val service = createService(resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
            entityStatementJwt(leaf, leaf, exp = earliestExpiry + 300),
            entityStatementJwt(leaf, anchor, exp = earliestExpiry),
            entityStatementJwt(anchor, anchor, exp = earliestExpiry + 900),
        ))))

        val result = service.validate(createRequest(leaf, anchor))

        assertTrue(result.trusted)
        assertEquals(Instant.fromEpochSeconds(earliestExpiry), result.expiresAt)
    }

    @Test
    fun expiredSignedChainCannotBeTrusted() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val expired = Clock.System.now().epochSeconds - 10
        val service = createService(resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
            entityStatementJwt(leaf, leaf, exp = expired),
            entityStatementJwt(leaf, anchor),
            entityStatementJwt(anchor, anchor),
        ))))

        assertFalse(service.validate(createRequest(leaf, anchor)).trusted)
    }

    @Test
    fun requiredMarkTypeUsesSubjectAdvertisedCompactJwtUnderSelectedAnchor() = runTest {
        val leaf = "https://leaf.example"
        val firstAnchor = "https://first-anchor.example"
        val selectedAnchor = "https://selected-anchor.example"
        val markType = "https://marks.example/member"
        val markJwt = trustMarkJwt(markType, leaf)
        var verifiedMark: VerifyTrustMarkArgs? = null
        val fetchedEntityConfigurations = mutableListOf<String>()
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf),
                entityStatementJwt(leaf, selectedAnchor),
                entityStatementJwt(selectedAnchor, selectedAnchor),
            ))),
            advertisedTrustMarks = listOf(TrustMark(trustMarkType = markType, trustMark = markJwt)),
            advertisedSubject = leaf,
            onVerifyMark = { verifiedMark = it },
            onGetEntityConfiguration = { fetchedEntityConfigurations += it },
        )

        val result = service.validate(createRequest(leaf, "$firstAnchor,$selectedAnchor", markType))

        assertTrue(result.trusted)
        assertEquals(markJwt, verifiedMark?.trustMark)
        assertEquals(leaf, verifiedMark?.subject)
        assertEquals(selectedAnchor, verifiedMark?.trustAnchorConfig?.sub)
        assertTrue(leaf in fetchedEntityConfigurations)
    }

    @Test
    fun absentRequiredMarkIsNotSatisfiedByAValidatorAcceptingTheTypeString() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        var verifyCalls = 0
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf),
                entityStatementJwt(leaf, anchor),
                entityStatementJwt(anchor, anchor),
            ))),
            onVerifyMark = { verifyCalls++ },
        )

        val result = service.validate(createRequest(leaf, anchor, "https://marks.example/member"))

        assertFalse(result.trusted)
        assertEquals(0, verifyCalls)
    }

    @Test
    fun verifiedDifferentMarkTypeDoesNotSatisfyRequiredType() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf),
                entityStatementJwt(leaf, anchor),
                entityStatementJwt(anchor, anchor),
            ))),
            advertisedTrustMarks = listOf(TrustMark(
                trustMarkType = "https://marks.example/other",
                trustMark = trustMarkJwt("https://marks.example/other", leaf),
            )),
            advertisedSubject = leaf,
        )

        assertFalse(service.validate(createRequest(leaf, anchor, "https://marks.example/member")).trusted)
    }

    @Test
    fun advertisedMemberEntryWithDifferentJwtTypeDoesNotSatisfyRequiredType() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val requiredType = "https://marks.example/member"
        val jwtType = "https://marks.example/other"
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf),
                entityStatementJwt(leaf, anchor),
                entityStatementJwt(anchor, anchor),
            ))),
            advertisedTrustMarks = listOf(TrustMark(requiredType, trustMarkJwt(jwtType, leaf))),
            advertisedSubject = leaf,
        )

        assertFalse(service.validate(createRequest(leaf, anchor, requiredType)).trusted)
    }

    @Test
    fun rejectedAdvertisedMarkDoesNotSatisfyRequiredType() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val markType = "https://marks.example/member"
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf),
                entityStatementJwt(leaf, anchor),
                entityStatementJwt(anchor, anchor),
            ))),
            advertisedTrustMarks = listOf(TrustMark(markType, trustMarkJwt(markType, leaf))),
            advertisedSubject = leaf,
            verifyTrustMarkResult = Ok(TrustMarkValidationResponse(isValid = false)),
        )

        assertFalse(service.validate(createRequest(leaf, anchor, markType)).trusted)
    }

    @Test
    fun acceptedMarkExpiryBoundsTrustResult() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val markType = "https://marks.example/member"
        val markExpiry = Clock.System.now().epochSeconds + 300
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf),
                entityStatementJwt(leaf, anchor),
                entityStatementJwt(anchor, anchor),
            ))),
            advertisedTrustMarks = listOf(TrustMark(markType, trustMarkJwt(markType, leaf, markExpiry))),
            advertisedSubject = leaf,
        )

        val result = service.validate(createRequest(leaf, anchor, markType))

        assertTrue(result.trusted)
        assertEquals(Instant.fromEpochSeconds(markExpiry), result.expiresAt)
    }

    @Test
    fun expiredAdvertisedMarkCannotBeTrustedEvenWhenVerifierReturnsValid() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val markType = "https://marks.example/member"
        val now = Clock.System.now().epochSeconds
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf, exp = now + 600),
                entityStatementJwt(leaf, anchor, exp = now + 600),
                entityStatementJwt(anchor, anchor, exp = now + 600),
            ))),
            advertisedTrustMarks = listOf(TrustMark(markType, trustMarkJwt(markType, leaf, now - 10))),
            advertisedSubject = leaf,
            verifyTrustMarkResult = Ok(TrustMarkValidationResponse(isValid = true)),
        )

        assertFalse(service.validate(createRequest(leaf, anchor, markType)).trusted)
    }

    @Test
    fun cacheIdentityIncludesMaxDepthAndAnchorPreferenceOrder() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val otherAnchor = "https://other-anchor.example"
        var resolveCalls = 0
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf),
                entityStatementJwt(leaf, anchor),
                entityStatementJwt(anchor, anchor),
            ))),
            onResolve = { resolveCalls++ },
        )

        val depthThree = createRequest(leaf, "$anchor,$otherAnchor", maxChainDepth = 3)
        val depthFive = createRequest(leaf, "$anchor,$otherAnchor", maxChainDepth = 5)
        val reversedAnchors = createRequest(leaf, "$otherAnchor,$anchor", maxChainDepth = 5)
        assertTrue(service.validate(depthThree).trusted)
        assertTrue(service.validate(depthThree).trusted)
        assertTrue(service.validate(depthFive).trusted)
        assertTrue(service.validate(depthFive).trusted)
        assertTrue(service.validate(reversedAnchors).trusted)
        assertTrue(service.validate(reversedAnchors).trusted)
        assertEquals(3, resolveCalls)
    }

    @Test
    fun expiredCachedTrustEvidenceIsResolvedAgain() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val now = Clock.System.now().epochSeconds
        val cacheManager = NoOpCacheManager(TrustValidationResult(
            trusted = true,
            status = TrustStatus.TRUSTED,
            expiresAt = Instant.fromEpochSeconds(now - 30),
        ))
        var resolveCalls = 0
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf, exp = now + 600),
                entityStatementJwt(leaf, anchor, exp = now + 600),
                entityStatementJwt(anchor, anchor, exp = now + 600),
            ))),
            onResolve = { resolveCalls++ },
            cacheManager = cacheManager,
        )

        val result = service.validate(createRequest(leaf, anchor))

        assertEquals(1, resolveCalls)
        assertTrue(result.trusted)
        assertEquals(Instant.fromEpochSeconds(now + 600), result.expiresAt)
        val cachedFreshResult = service.validate(createRequest(leaf, anchor))
        assertEquals(1, resolveCalls)
        assertEquals(Instant.fromEpochSeconds(now + 600), cachedFreshResult.expiresAt)
    }

    @Test
    fun evidenceExpiringDuringEntityEnrichmentCannotReturnTrusted() = runTest {
        val leaf = "https://leaf.example"
        val anchor = "https://anchor.example"
        val expiry = Clock.System.now().epochSeconds + 3
        val cacheManager = NoOpCacheManager()
        var enrichmentFetches = 0
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(trustChain = listOf(
                entityStatementJwt(leaf, leaf, exp = expiry),
                entityStatementJwt(leaf, anchor, exp = expiry),
                entityStatementJwt(anchor, anchor, exp = expiry),
            ))),
            onGetEntityConfiguration = {
                enrichmentFetches++
                // Wait for this evidence's actual expiry, not a guessed fixed delay.
                withContext(Dispatchers.Default) {
                    withTimeout(10_000) {
                        while (Clock.System.now().epochSeconds < expiry) delay(10)
                    }
                }
            },
            cacheManager = cacheManager,
        )
        val request = createRequest(leaf, anchor).copy(
            entityDiscovery = EntityDiscoveryOptions(enabled = true),
        )

        val result = service.validate(request)

        assertEquals(1, enrichmentFetches)
        assertFalse(result.trusted)
        assertFalse(cacheManager.appWrites.any { it.trusted })
    }

    @Test
    fun requiredTrustMarksChangeResultAndCacheKey() = runTest {
        val leaf = "https://entity.example.com"
        val anchor = "https://anchor.example.com"
        val markType = "https://tm.example/member"
        var resolveCalls = 0
        val service = createService(
            resolveResult = Ok(TrustChainResolveResponse(
                trustChain = listOf(entityStatementJwt(leaf, leaf), entityStatementJwt(leaf, anchor))
            )),
            verifyResult = Ok(VerifyTrustChainResponse(isValid = true)),
            verifyTrustMarkResult = Ok(TrustMarkValidationResponse(isValid = true)),
            advertisedTrustMarks = listOf(TrustMark(markType, trustMarkJwt(markType, leaf))),
            advertisedSubject = leaf,
            onResolve = { resolveCalls++ },
        )
        val unmarked = service.validate(createRequest(leaf, trustAnchors = anchor))
        val marked = service.validate(createRequest(
            leaf,
            trustAnchors = anchor,
            requiredTrustMarks = markType,
        ))
        assertTrue(unmarked.trusted)
        assertTrue(marked.trusted)
        assertEquals(2, resolveCalls)
    }

    @Test
    fun getTrustAnchorsResolvesEntityConfigurations() = runTest {
        val service = createService(
            configTrustAnchors = listOf("https://anchor1.example.com", "https://anchor2.example.com")
        )
        val anchors = service.getTrustAnchors()
        assertEquals(2, anchors.size)
        assertEquals("https://anchor1.example.com", anchors[0].id)
        assertEquals("https://anchor2.example.com", anchors[1].id)
        assertEquals(TrustAnchor.TYPE_OPENID_FED_ENTITY, anchors[0].type)
        assertEquals("key-1", anchors[0].keyInfo.kid)
    }

    @Test
    fun supportsOpenIdFederationContextOnly() {
        val supportedTypes = setOf(TrustContext.TYPE_OPENID_FEDERATION)
        assertTrue(TrustContext.TYPE_OPENID_FEDERATION in supportedTypes)
        assertFalse(TrustContext.TYPE_DID in supportedTypes)
        assertFalse(TrustContext.TYPE_X509 in supportedTypes)
        assertFalse(TrustContext.TYPE_ETSI_TSL in supportedTypes)
    }

    // -- Helpers --

    private fun createRequest(
        entityIdentifier: String,
        trustAnchors: String? = null,
        requiredTrustMarks: String? = null,
        maxChainDepth: Int? = null,
    ): TrustValidationRequest {
        val params = mutableMapOf("entityIdentifier" to entityIdentifier)
        if (trustAnchors != null) params["trustAnchors"] = trustAnchors
        if (requiredTrustMarks != null) params["requiredTrustMarks"] = requiredTrustMarks
        if (maxChainDepth != null) params["maxChainDepth"] = maxChainDepth.toString()
        return TrustValidationRequest(
            identifier = ExternalIdentifierOIDFEntityIdOpts(identifier = entityIdentifier),
            context = TrustContext(
                type = TrustContext.TYPE_OPENID_FEDERATION,
                parameters = params
            )
        )
    }

    private fun createService(
        configTrustAnchors: List<String> = listOf("https://default-anchor.example.com"),
        resolveResult: IdkResult<TrustChainResolveResponse, FederationError> = Ok(
            TrustChainResolveResponse(trustChain = listOf("jwt1", "jwt2"))
        ),
        verifyResult: IdkResult<VerifyTrustChainResponse, FederationError> = Ok(
            VerifyTrustChainResponse(isValid = true)
        ),
        onResolve: ((ResolveTrustChainArgs) -> Unit)? = null,
        onVerify: ((VerifyTrustChainArgs) -> Unit)? = null,
        verifyTrustMarkResult: IdkResult<TrustMarkValidationResponse, FederationError> = Ok(
            TrustMarkValidationResponse(isValid = true)
        ),
        advertisedTrustMarks: List<TrustMark>? = null,
        advertisedSubject: String? = null,
        onVerifyMark: ((VerifyTrustMarkArgs) -> Unit)? = null,
        onGetEntityConfiguration: (suspend (String) -> Unit)? = null,
        cacheManager: CacheManager = NoOpCacheManager(),
    ): OidfTrustValidationService {
        val resolveCmd = object : ResolveTrustChainCommand {
            override val isEnabled: Boolean get() = true
            override suspend fun resolveTrustChain(
                entityIdentifier: String, trustAnchors: Array<String>, maxDepth: Int
            ): IdkResult<TrustChainResolveResponse, FederationError> {
                val args = ResolveTrustChainArgs(entityIdentifier, trustAnchors, maxDepth)
                onResolve?.invoke(args)
                return resolveResult
            }
            override suspend fun execute(args: ResolveTrustChainArgs): IdkResult<TrustChainResolveResponse, FederationError> =
                resolveTrustChain(args.entityIdentifier, args.trustAnchors, args.maxDepth)
            override suspend fun supports(args: Any): Boolean = args is ResolveTrustChainArgs
            override val id: String get() = ResolveTrustChainCommand.COMMAND_ID
        }

        val verifyCmd = object : VerifyTrustChainCommand {
            override val isEnabled: Boolean get() = true
            override suspend fun verifyTrustChain(
                trustChain: Array<String>,
                trustAnchor: String?,
                currentTime: Long?,
                trustAnchorPublicKeys: List<com.sphereon.openid.fed.openapi.models.Jwk>?
            ): IdkResult<VerifyTrustChainResponse, FederationError> {
                onVerify?.invoke(VerifyTrustChainArgs(trustChain, trustAnchor, currentTime, trustAnchorPublicKeys))
                return verifyResult
            }
            override suspend fun execute(args: VerifyTrustChainArgs): IdkResult<VerifyTrustChainResponse, FederationError> =
                verifyTrustChain(args.trustChain, args.trustAnchor, args.currentTime)
            override suspend fun supports(args: Any): Boolean = args is VerifyTrustChainArgs
            override val id: String get() = VerifyTrustChainCommand.COMMAND_ID
        }

        val trustMarkCmd = object : VerifyTrustMarkCommand {
            override val isEnabled: Boolean get() = true
            override suspend fun verifyTrustMark(
                trustMark: String,
                trustAnchorConfig: EntityConfigurationStatement,
                currentTime: Long?,
                subject: String?
            ): IdkResult<TrustMarkValidationResponse, FederationError> {
                onVerifyMark?.invoke(VerifyTrustMarkArgs(trustMark, trustAnchorConfig, currentTime, subject))
                return verifyTrustMarkResult
            }
            override suspend fun execute(args: VerifyTrustMarkArgs): IdkResult<TrustMarkValidationResponse, FederationError> =
                verifyTrustMark(args.trustMark, args.trustAnchorConfig, args.currentTime, args.subject)
            override suspend fun supports(args: Any): Boolean = args is VerifyTrustMarkArgs
            override val id: String get() = VerifyTrustMarkCommand.COMMAND_ID
        }

        val configProvider = object : TrustConfigProvider {
            override fun getTrustConfig() = TrustConfig(
                anchors = TrustAnchorsConfig(
                    oidfed = OidfTrustConfig(
                        enabled = true,
                        trustAnchors = configTrustAnchors,
                        maxChainDepth = 5
                    )
                )
            )
        }

        val getEntityConfigCmd = object : GetEntityConfigurationCommand {
            override val isEnabled: Boolean get() = true
            override suspend fun getEntityConfiguration(entityIdentifier: String): IdkResult<EntityConfigurationStatement, FederationError> {
                onGetEntityConfiguration?.invoke(entityIdentifier)
                return Ok(EntityConfigurationStatement(
                    iss = entityIdentifier,
                    sub = entityIdentifier,
                    exp = 9999999999.0,
                    iat = 1000000000.0,
                    trustMarks = if (entityIdentifier == advertisedSubject) advertisedTrustMarks else null,
                    jwks = com.sphereon.openid.fed.openapi.models.BaseStatementJwks(
                        propertyKeys = listOf(
                            com.sphereon.openid.fed.openapi.models.Jwk(
                                kty = "EC",
                                kid = "key-1",
                                crv = "P-256",
                                x = "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU",
                                y = "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"
                            )
                        )
                    )
                ))
            }
            override suspend fun execute(args: com.sphereon.openid.fed.client.command.entityConfiguration.GetEntityConfigurationArgs): IdkResult<EntityConfigurationStatement, FederationError> =
                getEntityConfiguration(args.entityIdentifier)
            override suspend fun supports(args: Any): Boolean = true
            override val id: String get() = GetEntityConfigurationCommand.COMMAND_ID
        }

        return OidfTrustValidationService(
            resolveTrustChainCommand = resolveCmd,
            verifyTrustChainCommand = verifyCmd,
            verifyTrustMarkCommand = trustMarkCmd,
            getEntityConfigurationCommand = getEntityConfigCmd,
            trustAnchorKeys = object : TrustAnchorKeyResolver {
                override suspend fun publishedKeys(trustAnchor: String, currentTimeSeconds: Long) =
                    getEntityConfigCmd.getEntityConfiguration(trustAnchor).map { it.jwks.propertyKeys.orEmpty() }
            },
            trustConfigProvider = configProvider,
            cacheManager = cacheManager,
            execution = TestSessionExecution(createAnonymousSessionContext("oidfed-test", correlationId = "oidfed-test")),
            entityInfoExtractor = OidfEntityInfoExtractor(getEntityConfigCmd)
        )
    }

    // -- Test infrastructure --

    /**
     * In-memory CacheManager for testing the bridge's cache identity and expiry behavior.
     * Matches the published CacheManager API from IDK 0.25.0-SNAPSHOT.
     */
    private class NoOpCacheManager(
        private val seededAppResult: TrustValidationResult? = null,
    ) : CacheManager {
        val appWrites = mutableListOf<TrustValidationResult>()
        override fun registerBackend(backend: CacheBackend) = Unit
        override fun getBackends(): List<CacheBackend> = emptyList()
        override fun getBackend(id: String): CacheBackend? = null
        override fun hasDistributedBackend(): Boolean = false
        override fun hasLocalBackend(): Boolean = false
        override fun registerNamespace(requirements: CacheRequirements) = Unit
        override fun getRequirements(namespace: String): CacheRequirements? = null
        override fun getNamespaces(): Set<String> = emptySet()
        override fun <K : Any, V : Any> createCache(
            requirements: CacheRequirements,
            keySerializer: CacheSerializer<K>,
            valueSerializer: CacheSerializer<V>,
        ): ScopedCache<K, V> = NoOpScopedCache(requirements.namespace, seededAppResult, appWrites)

        override fun <K : Any, V : Any> getCache(namespace: String): ScopedCache<K, V>? = null
        override fun getAllCaches(): List<ScopedCache<*, *>> = emptyList()
        override fun aggregateStats(): Map<String, CacheStatistics> = emptyMap()
        override suspend fun invalidateTenant(tenantId: String) = Unit
        override suspend fun invalidatePrincipal(tenantId: String, principalId: String) = Unit
        override suspend fun clearAll() = Unit
        override suspend fun isHealthy(): Boolean = true
    }

    private class NoOpScopedCache<K : Any, V : Any>(
        override val namespace: String,
        seededAppResult: TrustValidationResult? = null,
        private val appWrites: MutableList<TrustValidationResult>,
    ) : ScopedCache<K, V> {
        private val appEntries = mutableMapOf<K, V>()
        private var pendingSeed = seededAppResult
        override val backendId: String = "noop"
        @Suppress("UNCHECKED_CAST")
        override suspend fun getApp(key: K): V? {
            val seed = pendingSeed
            pendingSeed = null
            return (seed as V?) ?: appEntries[key]
        }
        override suspend fun putApp(key: K, value: V, ttl: Duration?) {
            appEntries[key] = value
            if (value is TrustValidationResult) appWrites += value
        }
        override suspend fun removeApp(key: K): Boolean = appEntries.remove(key) != null
        override suspend fun containsApp(key: K): Boolean = key in appEntries
        override suspend fun getTenant(tenantId: String, key: K): V? = null
        override suspend fun putTenant(tenantId: String, key: K, value: V, ttl: Duration?) = Unit
        override suspend fun removeTenant(tenantId: String, key: K): Boolean = false
        override suspend fun containsTenant(tenantId: String, key: K): Boolean = false
        override suspend fun getPrincipal(tenantId: String, principalId: String, key: K): V? = null
        override suspend fun putPrincipal(tenantId: String, principalId: String, key: K, value: V, ttl: Duration?) = Unit
        override suspend fun removePrincipal(tenantId: String, principalId: String, key: K): Boolean = false
        override suspend fun containsPrincipal(tenantId: String, principalId: String, key: K): Boolean = false
        override suspend fun invalidateApp() { appEntries.clear() }
        override suspend fun invalidateTenant(tenantId: String) = Unit
        override suspend fun invalidatePrincipal(tenantId: String, principalId: String) = Unit
        override suspend fun invalidateByKeyPattern(pattern: String) = Unit
        override suspend fun get(key: ScopedKey<K>): V? = null
        override suspend fun put(key: ScopedKey<K>, value: V, ttl: Duration?) = Unit
        override suspend fun getOrPut(key: ScopedKey<K>, ttl: Duration?, compute: suspend () -> V): V = compute()
        override suspend fun remove(key: ScopedKey<K>): Boolean = false
        override suspend fun contains(key: ScopedKey<K>): Boolean = false
        override suspend fun clear() { appEntries.clear() }
        override suspend fun size(): Long = appEntries.size.toLong()
        override fun stats(): CacheStatistics = CacheStatistics()
        override suspend fun getMany(keys: Collection<ScopedKey<K>>): Map<ScopedKey<K>, V> = emptyMap()
        override suspend fun putMany(entries: Map<ScopedKey<K>, V>, ttl: Duration?) = Unit
        override suspend fun removeMany(keys: Collection<ScopedKey<K>>): Int = 0
    }

    private class TestSessionExecution(
        override val sessionContext: SessionContext
    ) : SessionExecution {
        override val sessionContextManager: SessionContextManager get() = throw NotImplementedError()
        override val log: SessionLogService = TestLogService(sessionContext)
        override val conf: ContextConfig = TestContextConfig()
    }

    private class TestContextConfig : ContextConfig {
        override val app: AppConfigService get() = throw NotImplementedError()
        override val tenant: TenantConfigService get() = throw NotImplementedError()
        override val principal: PrincipalConfigService get() = throw NotImplementedError()
        override fun conf(level: ConfigLevel): ConfigService = throw NotImplementedError()
    }

    private class TestLogService(
        override val sessionContext: SessionContext = NoOpSessionContext
    ) : SessionLogService {
        override val logManager: SessionLogManager = TestLogManager(sessionContext)
        override val scope: IdkScope = IdkScope.SESSION
        override val id: String = "test-log"
        override val isEnabled: Boolean = false
        override suspend fun setConfig(config: LoggerConfig): SessionLogService = this
        override fun executeAsync(message: LogMessage) = IdkOkResult(Unit)
        override fun toAsync(): AsyncLogService = TestAsyncLogService(sessionContext)
    }

    private class TestLogManager(private val ctx: SessionContext) : SessionLogManager {
        override suspend fun setGlobalConfig(config: LoggerConfig): SessionLogManager = this
        override suspend fun getGlobalConfig(): LoggerConfig = LoggerConfig.Default
        override fun withTagAsync(tag: String, config: LoggerConfig?): AsyncLogService = TestAsyncLogService(ctx)
        override fun withTag(tag: String, config: LoggerConfig?): SessionLogService = TestLogService(ctx)
    }

    private class TestAsyncLogService(
        override val sessionContext: SessionContext = NoOpSessionContext
    ) : AsyncLogService {
        override val scope: IdkScope = IdkScope.SESSION
        override val id: String = "test-async-log"
        override val isEnabled: Boolean = false
        override suspend fun setConfig(config: LoggerConfig): AsyncLogService = this
        override suspend fun execute(args: LogMessage) = IdkOkResult(Unit)
        override fun toSync(): SessionLogService = TestLogService(sessionContext)
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun entityStatementJwt(
    sub: String,
    iss: String,
    exp: Long = Clock.System.now().epochSeconds + 3600,
): String {
    val iat = Clock.System.now().epochSeconds - 60
    val payload = """{
        "iss":"$iss",
        "sub":"$sub",
        "iat":$iat,
        "exp":$exp,
        "jwks":{"keys":[{"kty":"EC","kid":"key-1","crv":"P-256","x":"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU","y":"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0"}]},
        "metadata":{"federation_entity":{"federation_fetch_endpoint":"$iss/fetch"}}
    }"""
    return compactTestJwt("entity-statement+jwt", payload)
}

@OptIn(ExperimentalEncodingApi::class)
private fun trustMarkJwt(
    type: String,
    subject: String,
    exp: Long = Clock.System.now().epochSeconds + 3600,
): String {
    val iat = Clock.System.now().epochSeconds - 60
    return compactTestJwt("trust-mark+jwt", """{
        "iss":"https://mark-issuer.example",
        "sub":"$subject",
        "trust_mark_type":"$type",
        "iat":$iat,
        "exp":$exp
    }""")
}

@OptIn(ExperimentalEncodingApi::class)
private fun compactTestJwt(typ: String, payload: String): String {
    val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val header = encoder.encode("""{"alg":"ES256","typ":"$typ","kid":"key-1"}""".encodeToByteArray())
    return "$header.${encoder.encode(payload.encodeToByteArray())}.fixture-signature"
}
