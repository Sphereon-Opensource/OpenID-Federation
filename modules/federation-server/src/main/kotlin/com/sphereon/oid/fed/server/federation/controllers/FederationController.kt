package com.sphereon.oid.fed.server.federation.controllers

import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.ResolveResponse
import com.sphereon.oid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkStatusResponse
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.JwkService
import com.sphereon.oid.fed.services.ResolutionService
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.TrustMarkService
import com.sphereon.oid.fed.services.mappers.account.toDTO
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping()
class FederationController(
    private val accountService: AccountService,
    private val subordinateService: SubordinateService,
    private val trustMarkService: TrustMarkService,
    private val jwkService: JwkService,
    private val resolutionService: ResolutionService
) {
    private val accountQueries = Persistence.accountQueries
    private val entityConfigurationStatementQueries = Persistence.entityConfigurationStatementQueries

    // Extracted helper: Get account by username with a default value of "root"
    private fun getAccountOrThrow(username: String) =
        accountQueries.findByUsername(username).executeAsOneOrNull() ?: throw NotFoundException("Account not found")

    // Extracted helper: Create TrustMarkListRequest
    private fun createTrustMarkListRequest(sub: String?, trustMarkId: String) =
        TrustMarkListRequest(sub = sub, trustMarkId = trustMarkId)

    @GetMapping("/.well-known/openid-federation", produces = ["application/entity-statement+jwt"])
    fun getRootEntityConfigurationStatement(): String {
        val account = getAccountOrThrow("root")
        val entityConfigurationStatement = entityConfigurationStatementQueries
            .findLatestByAccountId(account.id)
            .executeAsOneOrNull() ?: throw NotFoundException("Entity Configuration Statement not found")
        return entityConfigurationStatement.statement
    }

    @GetMapping("/{username}/.well-known/openid-federation", produces = ["application/entity-statement+jwt"])
    fun getAccountEntityConfigurationStatement(@PathVariable username: String): String {
        val account = getAccountOrThrow(username)
        val entityConfigurationStatement = entityConfigurationStatementQueries
            .findLatestByAccountId(account.id)
            .executeAsOneOrNull() ?: throw NotFoundException("Entity Configuration Statement not found")
        return entityConfigurationStatement.statement
    }

    @GetMapping("/list")
    fun getRootSubordinatesList(): Array<String> {
        val account = getAccountOrThrow("root")
        return subordinateService.findSubordinatesByAccountAsArray(account.toDTO())
    }

    @GetMapping("/{username}/list")
    fun getSubordinatesList(@PathVariable username: String): Array<String> {
        val account = getAccountOrThrow(username)
        return subordinateService.findSubordinatesByAccountAsArray(account.toDTO())
    }

    @GetMapping("/fetch", produces = ["application/entity-statement+jwt"])
    fun getRootSubordinateStatement(@RequestParam("sub") sub: String): String {
        val account = getAccountOrThrow("root")
        val accountIss = accountService.getAccountIdentifierByAccount(account.toDTO())
        return subordinateService.fetchSubordinateStatement(accountIss, sub)
    }

    @GetMapping("/{username}/fetch", produces = ["application/entity-statement+jwt"])
    fun getSubordinateStatement(@PathVariable username: String, @RequestParam("sub") sub: String): String {
        val account = getAccountOrThrow(username)
        val accountIss = accountService.getAccountIdentifierByAccount(account.toDTO())
        return subordinateService.fetchSubordinateStatement(accountIss, sub)
    }

    @PostMapping("/trust-mark-status", produces = ["application/json"])
    fun getRootTrustMarkStatusEndpoint(@RequestBody request: TrustMarkStatusRequest): TrustMarkStatusResponse {
        val account = getAccountOrThrow("root")
        val status = trustMarkService.getTrustMarkStatus(account.toDTO(), request)
        return TrustMarkStatusResponse(active = status)
    }

    @PostMapping("/{username}/trust-mark-status", produces = ["application/json"])
    fun getTrustMarkStatusEndpoint(
        @PathVariable username: String,
        @RequestBody request: TrustMarkStatusRequest
    ): TrustMarkStatusResponse {
        val account = getAccountOrThrow(username)
        val status = trustMarkService.getTrustMarkStatus(account.toDTO(), request)
        return TrustMarkStatusResponse(active = status)
    }

    @GetMapping("/trust-mark-list", produces = ["application/json"])
    fun getRootTrustMarkListEndpoint(
        @RequestParam("sub") sub: String?,
        @RequestParam("trust_mark_id") trustMarkId: String
    ): Array<String> {
        val account = getAccountOrThrow("root")
        val request = createTrustMarkListRequest(sub, trustMarkId)
        return trustMarkService.getTrustMarkedSubs(account.toDTO(), request)
    }

    @GetMapping("/{username}/trust-mark-list", produces = ["application/json"])
    fun getTrustMarkListEndpoint(
        @PathVariable username: String,
        @RequestParam("sub") sub: String?,
        @RequestParam("trust_mark_id") trustMarkId: String
    ): Array<String> {
        val account = getAccountOrThrow(username)
        val request = createTrustMarkListRequest(sub, trustMarkId)
        return trustMarkService.getTrustMarkedSubs(account.toDTO(), request)
    }

    @GetMapping("/trust-mark", produces = ["application/trust-mark+jwt"])
    fun getRootTrustMarkEndpoint(@RequestBody request: TrustMarkRequest): String {
        val account = getAccountOrThrow("root")
        return trustMarkService.getTrustMark(account.toDTO(), request)
    }

    @GetMapping("/{username}/trust-mark", produces = ["application/trust-mark+jwt"])
    fun getTrustMarkEndpoint(@PathVariable username: String, @RequestBody request: TrustMarkRequest): String {
        val account = getAccountOrThrow(username)
        return trustMarkService.getTrustMark(account.toDTO(), request)
    }

    @GetMapping("/historical-keys", produces = ["application/jwk-set+jwt"])
    suspend fun getRootFederationHistoricalKeys(): String {
        val account = getAccountOrThrow("root")
        return jwkService.getFederationHistoricalKeysJwt(account.toDTO(), accountService)
    }

    @GetMapping("/{username}/historical-keys", produces = ["application/jwk-set+jwt"])
    suspend fun getFederationHistoricalKeys(@PathVariable username: String): String {
        val account = getAccountOrThrow(username)
        return jwkService.getFederationHistoricalKeysJwt(account.toDTO(), accountService)
    }

    @GetMapping("/resolve", produces = ["application/resolve-response+jwt"])
    suspend fun getRootResolveEndpoint(
        @RequestParam("sub") sub: String,
        @RequestParam("trust_anchor") trustAnchor: String,
        @RequestParam("entity_type", required = false) entityTypes: Array<String>?
    ): ResolveResponse {
        val account = getAccountOrThrow("root")
        // Additional logic for resolution should go here before returning the response
        return resolutionService.resolveEntity(account.toDTO(), sub, trustAnchor, entityTypes)
    }

    @GetMapping("/{username}/resolve", produces = ["application/resolve-response+jwt"])
    suspend fun getResolveEndpoint(
        @PathVariable username: String,
        @RequestParam("sub") sub: String,
        @RequestParam("trust_anchor") trustAnchor: String,
        @RequestParam("entity_type", required = false) entityTypes: Array<String>?
    ): ResolveResponse {
        val account = getAccountOrThrow(username)
        // Additional logic for resolution should go here before returning the response
        return resolutionService.resolveEntity(account.toDTO(), sub, trustAnchor, entityTypes)
    }
}
