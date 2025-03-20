package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.AuthorityHint
import com.sphereon.oid.fed.openapi.models.AuthorityHintsResponse
import com.sphereon.oid.fed.openapi.models.CreateAuthorityHint
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.AuthorityHintService
import com.sphereon.oid.fed.services.mappers.authorityHints.toAuthorityHintsResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/authority-hints")
class AuthorityHintController(
    private val authorityHintService: AuthorityHintService
) {
    @GetMapping
    fun getAuthorityHints(request: HttpServletRequest): AuthorityHintsResponse {
        val account = getAccountFromRequest(request)
        return authorityHintService.findByAccount(account).toAuthorityHintsResponse()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAuthorityHint(
        request: HttpServletRequest,
        @RequestBody body: CreateAuthorityHint
    ): AuthorityHint {
        val account = getAccountFromRequest(request)
        return authorityHintService.createAuthorityHint(account, body.identifier)
    }

    @DeleteMapping("/{id}")
    fun deleteAuthorityHint(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): AuthorityHint {
        val account = getAccountFromRequest(request)
        return authorityHintService.deleteAuthorityHint(account, id)
    }
}
