package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
import com.sphereon.oid.fed.services.KeyService
import com.sphereon.oid.fed.services.extensions.toJwkAdminDTO
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/accounts/{accountUsername}/keys")
class KeyController {
    private val keyService = KeyService()

    @PostMapping
    fun create(@PathVariable accountUsername: String): JwkAdminDTO {
        val key = keyService.create(accountUsername)
        return key.toJwkAdminDTO()
    }

    @GetMapping
    fun getKeys(@PathVariable accountUsername: String): List<JwkAdminDTO> {
        val keys = keyService.getKeys(accountUsername)
        return keys.map { it.toJwkAdminDTO() }
    }

    @DeleteMapping("/{keyId}")
    fun revokeKey(
        @PathVariable accountUsername: String,
        @PathVariable keyId: Int,
        @RequestParam reason: String?
    ): JwkAdminDTO {
        return keyService.revokeKey(accountUsername, keyId, reason)
    }
}