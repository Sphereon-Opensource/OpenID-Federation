package com.sphereon.oid.fed.server.federation.controllers

import com.sphereon.oid.fed.services.SubordinateService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping()
class FederationController {
    private val subordinateService = SubordinateService()

    @GetMapping("/.well-known/openid-federation")
    fun getRootEntityConfigurationStatement(): String {
        throw NotImplementedError()
    }

    @GetMapping("/{username}/.well-known/openid-federation")
    fun getAccountEntityConfigurationStatement(@PathVariable username: String): String {
        throw NotImplementedError()
    }

    @GetMapping("/list")
    fun getRootSubordinatesList(): Array<String> {
        return subordinateService.findSubordinatesByAccountAsArray("root")
    }

    @GetMapping("/{username}/list")
    fun getSubordinatesList(@PathVariable username: String): Array<String> {
        return subordinateService.findSubordinatesByAccountAsArray(username)
    }

    @GetMapping("/fetch")
    fun getSubordinateStatement(): List<String> {
        throw NotImplementedError()
    }
}
