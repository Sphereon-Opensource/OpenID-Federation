package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.AuthorityHintDTO
import com.sphereon.oid.fed.openapi.models.CreateAuthorityHintDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.AuthorityHintService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/authority-hints")
class AuthorityHintController(
    private val authorityHintService: AuthorityHintService
) {
    @GetMapping
    fun getAuthorityHints(request: HttpServletRequest): List<AuthorityHintDTO> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return authorityHintService.findByAccount(account)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAuthorityHint(
        request: HttpServletRequest,
        @RequestBody body: CreateAuthorityHintDTO
    ): AuthorityHintDTO {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return authorityHintService.createAuthorityHint(account, body.identifier)
    }

    @DeleteMapping("/{id}")
    fun deleteAuthorityHint(
        request: HttpServletRequest,
        @PathVariable id: Int
    ): AuthorityHintDTO {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return authorityHintService.deleteAuthorityHint(account, id)
    }
}
