package com.sphereon.oid.fed.server.federation.controllers

import com.sphereon.oid.fed.common.exceptions.NotFoundException
import com.sphereon.oid.fed.openapi.models.TrustMarkListRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkStatusRequest
import com.sphereon.oid.fed.openapi.models.TrustMarkStatusResponse
import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.SubordinateService
import com.sphereon.oid.fed.services.TrustMarkService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping()
class FederationController {
    private val accountQueries = Persistence.accountQueries
    private val entityConfigurationStatementQueries = Persistence.entityConfigurationStatementQueries
    private val subordinateService = SubordinateService()
    private val trustMarkService = TrustMarkService()

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
        return subordinateService.findSubordinatesByAccountAsArray("root")
    }

    @GetMapping("/{username}/list")
    fun getSubordinatesList(@PathVariable username: String): Array<String> {
        return subordinateService.findSubordinatesByAccountAsArray(username)
    }

    @GetMapping("/fetch", produces = ["application/entity-statement+jwt"])
    fun getRootSubordinateStatement(@RequestParam("sub") sub: String): String {
        return subordinateService.fetchSubordinateStatementByUsernameAndSubject("root", sub)
    }

    @GetMapping("/{username}/fetch", produces = ["application/entity-statement+jwt"])
    fun getSubordinateStatement(@PathVariable username: String, @RequestParam("sub") sub: String): String {
        return subordinateService.fetchSubordinateStatementByUsernameAndSubject(username, sub)
    }

    @GetMapping("/trust-mark-status", produces = ["application/json"])
    fun getRootTrustMarkStatusEndpoint(
        @RequestBody request: TrustMarkStatusRequest
    ): TrustMarkStatusResponse {
        val account = accountQueries.findByUsername("root").executeAsOne()
        val status = trustMarkService.getTrustMarkStatus(account, request)

        return TrustMarkStatusResponse(
            active = status,
        )
    }

    @GetMapping("/{username}/trust-mark-status", produces = ["application/json"])
    fun getTrustMarkStatusEndpoint(
        @PathVariable username: String,
        @RequestBody request: TrustMarkStatusRequest
    ): TrustMarkStatusResponse {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
        val status = trustMarkService.getTrustMarkStatus(account, request)

        return TrustMarkStatusResponse(
            active = status,
        )
    }

    @GetMapping("/trust-mark-list", produces = ["application/json"])
    fun getRootTrustMarkListEndpoint(
        @RequestBody request: TrustMarkListRequest
    ): Array<String> {
        val account = accountQueries.findByUsername("root").executeAsOne()
        return trustMarkService.getTrustMarkedSubs(account, request)
    }

    @GetMapping("/{username}/trust-mark-list", produces = ["application/json"])
    fun getTrustMarkListEndpoint(
        @PathVariable username: String,
        @RequestBody request: TrustMarkListRequest
    ): Array<String> {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw NotFoundException("Account not found")
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
}
