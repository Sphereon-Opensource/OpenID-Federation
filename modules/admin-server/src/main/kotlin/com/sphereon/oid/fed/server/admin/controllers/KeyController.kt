package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.JwkAdminDTO
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
    private val keyService = KeyService()

    @PostMapping
    fun create(@PathVariable username: String): JwkAdminDTO {
        val key = keyService.create(username)
        return key
    }

    @GetMapping
    fun getKeys(@PathVariable username: String): Array<JwkAdminDTO> {
        val keys = keyService.getKeys(username)
        return keys
    }

    @DeleteMapping("/{keyId}")
    fun revokeKey(
        @PathVariable username: String,
        @PathVariable keyId: Int,
        @RequestParam reason: String?
    ): JwkAdminDTO {
        return keyService.revokeKey(username, keyId, reason)
    }
}
