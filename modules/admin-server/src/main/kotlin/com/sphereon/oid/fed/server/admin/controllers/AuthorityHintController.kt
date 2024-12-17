package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateAuthorityHintDTO
import com.sphereon.oid.fed.persistence.models.AuthorityHint
import com.sphereon.oid.fed.services.AuthorityHintService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{username}/authority-hints")
class AuthorityHintController {
    private val authorityHintService = AuthorityHintService()

    @GetMapping
    fun getAuthorityHints(@PathVariable username: String): Array<AuthorityHint> {
        return authorityHintService.findByAccountUsername(username)
    }

    @PostMapping
    fun createAuthorityHint(
        @PathVariable username: String,
        @RequestBody body: CreateAuthorityHintDTO
    ): AuthorityHint {
        return authorityHintService.createAuthorityHint(username, body.identifier)
    }

    @DeleteMapping("/{id}")
    fun deleteAuthorityHint(
        @PathVariable username: String,
        @PathVariable id: Int
    ): AuthorityHint {
        return authorityHintService.deleteAuthorityHint(username, id)
    }
}
