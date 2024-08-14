package com.sphereon.oid.fed.server.federation.controllers

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping()
class FederationController {
    @GetMapping("/.well-known/openid-federation")
    fun getRootEntityConfigurationStatement(): String {
        throw NotImplementedError()
    }

    @GetMapping("/{username}/.well-known/openid-federation")
    fun getAccountEntityConfigurationStatement(@PathVariable username: String): String {
        throw NotImplementedError()
    }

    @GetMapping("/list")
    fun getSubordinatesList(): List<String> {
        throw NotImplementedError()
    }

    @GetMapping("/fetch")
    fun getSubordinateStatement(): List<String> {
        throw NotImplementedError()
    }
}
