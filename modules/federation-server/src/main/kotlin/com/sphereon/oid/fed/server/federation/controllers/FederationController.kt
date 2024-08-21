package com.sphereon.oid.fed.server.federation.controllers

import com.sphereon.oid.fed.persistence.Persistence
import com.sphereon.oid.fed.services.SubordinateService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping()
class FederationController {
    private val accountQueries = Persistence.accountQueries
    private val entityConfigurationStatementQueries = Persistence.entityConfigurationStatementQueries
    private val subordinateService = SubordinateService()

    @GetMapping("/.well-known/openid-federation")
    fun getRootEntityConfigurationStatement(): String {
        val account = accountQueries.findByUsername("root").executeAsOneOrNull()
            ?: throw IllegalArgumentException("Account not found")
        val entityConfigurationStatement =
            entityConfigurationStatementQueries.findLatestByAccountId(account.id).executeAsOneOrNull()
                ?: throw IllegalArgumentException("Entity Configuration Statement not found")

        return entityConfigurationStatement.statement
    }

    @GetMapping("/{username}/.well-known/openid-federation")
    fun getAccountEntityConfigurationStatement(@PathVariable username: String): String {
        val account = accountQueries.findByUsername(username).executeAsOneOrNull()
            ?: throw IllegalArgumentException("Account not found")
        val entityConfigurationStatement =
            entityConfigurationStatementQueries.findLatestByAccountId(account.id).executeAsOneOrNull()
                ?: throw IllegalArgumentException("Entity Configuration Statement not found")

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

    @GetMapping("/fetch")
    fun getSubordinateStatement(): List<String> {
        throw NotImplementedError()
    }
}
