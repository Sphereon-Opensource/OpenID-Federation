package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.JwkDto
import com.sphereon.oid.fed.persistence.models.Jwk
import com.sphereon.oid.fed.services.KeyService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/accounts/{accountUsername}/keys")
class KeyController {
    private val keyService = KeyService()

    @PostMapping
    fun create(@PathVariable accountUsername: String): Int {
        val key = keyService.create(accountUsername)
        return key.id
    }

    @GetMapping
    fun getKeys(@PathVariable accountUsername: String): List<JwkDto> {
        val keys = keyService.getKeys(accountUsername)
        return keys
    }
}