package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.KeyService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{username}/keys")
class KeyController {
    private val accountService = AccountService()
    private val keyService = KeyService()

    @PostMapping
    fun create(@PathVariable username: String): JwkAdminDTO {
        val account = accountService.getAccountByUsername(username)
        val key = keyService.create(account.id)
        return key
    }

    @GetMapping
    fun getKeys(@PathVariable username: String): Array<JwkAdminDTO> {
        val account = accountService.getAccountByUsername(username)
        val keys = keyService.getKeys(account.id)
        return keys
    }

    @DeleteMapping("/{keyId}")
    fun revokeKey(
        @PathVariable username: String,
        @PathVariable keyId: Int,
        @RequestParam reason: String?
    ): JwkAdminDTO {
        val account = accountService.getAccountByUsername(username)
        return keyService.revokeKey(account.id, keyId, reason)
    }
}
