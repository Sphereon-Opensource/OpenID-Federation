package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMarkDTO
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarkDTO
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.ReceivedTrustMarkService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{username}/received-trust-marks")
class ReceivedTrustMarkController {
    private val accountService = AccountService()
    private val receivedTrustMarkService = ReceivedTrustMarkService()

    @PostMapping
    fun create(@PathVariable username: String, @RequestBody dto: CreateReceivedTrustMarkDTO): ReceivedTrustMarkDTO {
        return receivedTrustMarkService.create(username, dto, accountService)
    }

    @GetMapping
    fun list(@PathVariable username: String): Array<ReceivedTrustMarkDTO> {
        return receivedTrustMarkService.list(username, accountService).toTypedArray()
    }

    @DeleteMapping("/{receivedTrustMarkId}")
    fun delete(
        @PathVariable username: String,
        @PathVariable receivedTrustMarkId: Int
    ): ReceivedTrustMarkDTO {
        return receivedTrustMarkService.delete(username, receivedTrustMarkId, accountService)
    }
}
