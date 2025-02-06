package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.AuthorityHint
import com.sphereon.oid.fed.openapi.models.CreateAuthorityHint
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.AuthorityHintService
import com.sphereon.oid.fed.services.mappers.toDTO
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/authority-hints")
class AuthorityHintController(
    private val authorityHintService: AuthorityHintService
) {
    @GetMapping
    fun getAuthorityHints(request: HttpServletRequest): List<AuthorityHint> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return authorityHintService.findByAccount(account)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAuthorityHint(
        request: HttpServletRequest,
        @RequestBody body: CreateAuthorityHint
    ): AuthorityHint {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return authorityHintService.createAuthorityHint(account, body.identifier).toDTO()
    }

    @DeleteMapping("/{id}")
    fun deleteAuthorityHint(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): AuthorityHint {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return authorityHintService.deleteAuthorityHint(account, id).toDTO()
    }
}
