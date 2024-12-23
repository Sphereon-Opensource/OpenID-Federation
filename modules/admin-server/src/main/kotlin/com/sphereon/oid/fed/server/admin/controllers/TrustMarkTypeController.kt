package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateTrustMarkTypeDTO
import com.sphereon.oid.fed.openapi.models.TrustMarkTypeDTO
import com.sphereon.oid.fed.openapi.models.UpdateTrustMarkTypeDTO
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
@RequestMapping("/accounts/{username}/trust-mark-types")
class TrustMarkTypeController {
    private val accountService = AccountService()
    private val trustMarkService = TrustMarkService()

    @GetMapping
    fun getTrustMarkTypes(@PathVariable username: String): List<TrustMarkTypeDTO> {
        return trustMarkService.findAllByAccount(accountService.usernameToAccountId(username))
    }

    @PostMapping
    fun createTrustMarkType(
        @PathVariable username: String,
        @RequestBody createDto: CreateTrustMarkTypeDTO
    ): TrustMarkTypeDTO {
        return trustMarkService.createTrustMarkType(
            username,
            createDto,
            accountService
        )
    }

    @GetMapping("/{id}")
    fun getTrustMarkTypeById(
        @PathVariable username: String,
        @PathVariable id: Int
    ): TrustMarkTypeDTO {
        return trustMarkService.findById(accountService.usernameToAccountId(username), id)
    }

    @PutMapping("/{id}")
    fun updateTrustMarkType(
        @PathVariable username: String,
        @PathVariable id: Int,
        @RequestBody updateDto: UpdateTrustMarkTypeDTO
    ): TrustMarkTypeDTO {
        return trustMarkService.updateTrustMarkType(accountService.usernameToAccountId(username), id, updateDto)
    }

    @DeleteMapping("/{id}")
    fun deleteTrustMarkType(
        @PathVariable username: String,
        @PathVariable id: Int
    ): TrustMarkTypeDTO {
        return trustMarkService.deleteTrustMarkType(accountService.usernameToAccountId(username), id)
    }
}
