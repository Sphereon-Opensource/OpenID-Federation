package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.TrustMarkDTO
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.TrustMarkService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{username}/trust-marks")
class TrustMarkController {
    private val accountService = AccountService()
    private val trustMarkService = TrustMarkService()

    @GetMapping
    fun getTrustMarks(
        @PathVariable username: String
    ): List<TrustMarkDTO> {
        return trustMarkService.getTrustMarksForAccount(
            accountId = accountService.usernameToAccountId(username)
        )
    }

    @PostMapping
    fun createTrustMark(
        @PathVariable username: String,
        @RequestBody body: CreateTrustMarkDTO
    ): TrustMarkDTO {
        return trustMarkService.createTrustMark(
            accountId = accountService.usernameToAccountId(username),
            body,
            accountService
        )
    }

    @DeleteMapping("/{trustMarkId}")
    fun deleteTrustMark(
        @PathVariable username: String,
        @PathVariable trustMarkId: Int,
    ): TrustMarkDTO {
        return trustMarkService.deleteTrustMark(
            accountId = accountService.usernameToAccountId(username),
            id = trustMarkId,
        )
    }
}
