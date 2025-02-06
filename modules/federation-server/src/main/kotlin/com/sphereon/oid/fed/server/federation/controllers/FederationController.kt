package com.sphereon.oid.fed.server.federation.controllers

import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.*
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.*
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping()
class FederationController(
    private val accountService: AccountService,
    private val subordinateService: SubordinateService,
    private val trustMarkService: TrustMarkService,
    private val jwkService: JwkService,
    private val resolveService: ResolveService
) {
    private val accountQueries = Persistence.accountQueries
    private val entityConfigurationStatementQueries = Persistence.entityConfigurationStatementQueries

    @GetMapping("/.well-known/openid-federation", produces = ["application/entity-statement+jwt"])
    fun getRootEntityConfigurationStatement(): String {
        val account = accountQueries.findByUsername("root").executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        val entityConfigurationStatement =
            entityConfigurationStatementQueries.findLatestByAccountId(account.id).executeAsOneOrNull()
                ?: throw NotFoundException("Entity Configuration Statement not found")

        return entityConfigurationStatement.statement
    }

    @GetMapping("/{username}/.well-known/openid-federation", produces = ["application/entity-statement+jwt"])
    fun getAccountEntityConfigurationStatement(@PathVariable username: String): String {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        val entityConfigurationStatement =
            entityConfigurationStatementQueries.findLatestByAccountId(account.id).executeAsOneOrNull()
                ?: throw NotFoundException("Entity Configuration Statement not found")

        return entityConfigurationStatement.statement
    }

    @GetMapping("/list")
    fun getRootSubordinatesList(): Array<String> {
        val account = accountQueries.findByUsername("root").executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        return subordinateService.findSubordinatesByAccountAsArray(account)
    }

    @GetMapping("/{username}/list")
    fun getSubordinatesList(@PathVariable username: String): Array<String> {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        return subordinateService.findSubordinatesByAccountAsArray(account)
    }

    @GetMapping("/fetch", produces = ["application/entity-statement+jwt"])
    fun getRootSubordinateStatement(@RequestParam("sub") sub: String): String {
        val account = accountQueries.findByUsername("root").executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        val accountIss = accountService.getAccountIdentifierByAccount(account)
        return subordinateService.fetchSubordinateStatement(accountIss, sub)
    }

    @GetMapping("/{username}/fetch", produces = ["application/entity-statement+jwt"])
    fun getSubordinateStatement(@PathVariable username: String, @RequestParam("sub") sub: String): String {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        val accountIss = accountService.getAccountIdentifierByAccount(account)
        return subordinateService.fetchSubordinateStatement(accountIss, sub)
    }

    @GetMapping("/trust-mark-status", produces = ["application/json"])
    fun getRootTrustMarkStatusEndpoint(
        @RequestParam("sub") sub: String,
        @RequestParam("trust_mark_id") trustMarkId: String
    ): TrustMarkStatusResponse {
        val account = accountQueries.findByUsername("root").executeAsOne()
        val request = TrustMarkStatusRequest(
            sub = sub,
            trustMarkId = trustMarkId
        )
        val status = trustMarkService.getTrustMarkStatus(account, request)

        return TrustMarkStatusResponse(
            active = status,
        )
    }

    @GetMapping("/{username}/trust-mark-status", produces = ["application/json"])
    fun getTrustMarkStatusEndpoint(
        @PathVariable username: String,
        @RequestParam("sub") sub: String,
        @RequestParam("trust_mark_id") trustMarkId: String
    ): TrustMarkStatusResponse {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        val request = TrustMarkStatusRequest(
            sub = sub,
            trustMarkId = trustMarkId
        )
        val status = trustMarkService.getTrustMarkStatus(account, request)

        return TrustMarkStatusResponse(
            active = status,
        )
    }

    @GetMapping("/trust-mark-list", produces = ["application/json"])
    fun getRootTrustMarkListEndpoint(
        @RequestParam("sub") sub: String?,
        @RequestParam("trust_mark_id") trustMarkId: String
    ): Array<String> {
        val account = accountQueries.findByUsername("root").executeAsOne()
        val request = TrustMarkListRequest(
            sub = sub,
            trustMarkId = trustMarkId
        )
        return trustMarkService.getTrustMarkedSubs(account, request)
    }

    @GetMapping("/{username}/trust-mark-list", produces = ["application/json"])
    fun getTrustMarkListEndpoint(
        @PathVariable username: String,
        @RequestParam("sub") sub: String?,
        @RequestParam("trust_mark_id") trustMarkId: String
    ): Array<String> {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        val request = TrustMarkListRequest(
            sub = sub,
            trustMarkId = trustMarkId
        )
        return trustMarkService.getTrustMarkedSubs(account, request)
    }

    @GetMapping("/trust-mark", produces = ["application/trust-mark+jwt"])
    fun getRootTrustMarkEndpoint(
        @RequestBody request: TrustMarkRequest
    ): String {
        val account = accountQueries.findByUsername("root").executeAsOne()
        return trustMarkService.getTrustMark(account, request)
    }

    @GetMapping("/{username}/trust-mark", produces = ["application/trust-mark+jwt"])
    fun getTrustMarkEndpoint(
        @PathVariable username: String,
        @RequestBody request: TrustMarkRequest
    ): String {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        return trustMarkService.getTrustMark(account, request)
    }

    @GetMapping("/historical-keys", produces = ["application/jwk-set+jwt"])
    fun getRootFederationHistoricalKeys(): String {
        val account = accountQueries.findByUsername("root").executeAsOne()
        return jwkService.getFederationHistoricalKeysJwt(account, accountService)
    }

    @GetMapping("/{username}/historical-keys", produces = ["application/jwk-set+jwt"])
    fun getFederationHistoricalKeys(@PathVariable username: String): String {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        return jwkService.getFederationHistoricalKeysJwt(account, accountService)
    }

    @GetMapping("/resolve", produces = ["application/resolve-response+jwt"])
    suspend fun getRootResolveEndpoint(
        @RequestParam("sub") sub: String,
        @RequestParam("trust_anchor") trustAnchor: String,
        @RequestParam("entity_type", required = false) entityTypes: Array<String>?
    ): ResolveResponse {
        val account = accountQueries.findByUsername("root").executeAsOne()
        return resolveService.resolveEntity(account, sub, trustAnchor, entityTypes)
    }

    @GetMapping("/{username}/resolve", produces = ["application/resolve-response+jwt"])
    suspend fun getResolveEndpoint(
        @PathVariable username: String,
        @RequestParam("sub") sub: String,
        @RequestParam("trust_anchor") trustAnchor: String,
        @RequestParam("entity_type", required = false) entityTypes: Array<String>?
    ): ResolveResponse {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")

        return resolveService.resolveEntity(account, sub, trustAnchor, entityTypes)
    }
}
