package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateTrustMarkDefinitionDTO
import com.sphereon.oid.fed.openapi.models.TrustMarkDefinitionDTO
import com.sphereon.oid.fed.openapi.models.UpdateTrustMarkDefinitionDTO
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.TrustMarkService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{username}/trust-mark-definitions")
class TrustMarkDefinitionController {
    private val accountService = AccountService()
    private val trustMarkService = TrustMarkService()

    @GetMapping
    fun getTrustMarkDefinitions(@PathVariable username: String): List<TrustMarkDefinitionDTO> {
        return trustMarkService.findAllByAccount(accountService.usernameToAccountId(username))
    }

    @PostMapping
    fun createTrustMarkDefinition(
        @PathVariable username: String,
        @RequestBody createDto: CreateTrustMarkDefinitionDTO
    ): TrustMarkDefinitionDTO {
        return trustMarkService.createTrustMarkDefinition(accountService.usernameToAccountId(username), createDto)
    }

    @GetMapping("/{id}")
    fun getTrustMarkDefinitionById(
        @PathVariable username: String,
        @PathVariable id: Int
    ): TrustMarkDefinitionDTO {
        return trustMarkService.findById(accountService.usernameToAccountId(username), id)
    }

    @PutMapping("/{id}")
    fun updateTrustMarkDefinition(
        @PathVariable username: String,
        @PathVariable id: Int,
        @RequestBody updateDto: UpdateTrustMarkDefinitionDTO
    ): TrustMarkDefinitionDTO {
        return trustMarkService.updateTrustMarkDefinition(accountService.usernameToAccountId(username), id, updateDto)
    }

    @DeleteMapping("/{id}")
    fun deleteTrustMarkDefinition(
        @PathVariable username: String,
        @PathVariable id: Int
    ): TrustMarkDefinitionDTO {
        return trustMarkService.deleteTrustMarkDefinition(accountService.usernameToAccountId(username), id)
    }
}
