package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.JwkDto
import com.sphereon.oid.fed.services.KeyService
import com.sphereon.oid.fed.services.extensions.toJwkDTO
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/accounts/{accountUsername}/keys")
class KeyController {
    private val keyService = KeyService()

    @PostMapping
    fun create(@PathVariable accountUsername: String): JwkDto {
        val key = keyService.create(accountUsername)
        return key.toJwkDTO()
    }

    @GetMapping
    fun getKeys(@PathVariable accountUsername: String): List<JwkDto> {
        val keys = keyService.getKeys(accountUsername)
        return keys
    }

    @DeleteMapping("/{keyId}")
    fun revokeKey(
        @PathVariable accountUsername: String,
        @PathVariable keyId: Int,
        @RequestParam reason: String?
    ): JwkDto {
        return keyService.revokeKey(accountUsername, keyId, reason)
    }
}