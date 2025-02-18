package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.AccountJwk
import com.sphereon.oid.fed.services.JwkService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/keys")
class KeyController(
    private val jwkService: JwkService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(request: HttpServletRequest): AccountJwk {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return jwkService.createKey(account)
    }

    @GetMapping
    fun getKeys(request: HttpServletRequest): Array<AccountJwk> {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return jwkService.getKeys(account)
    }

    @DeleteMapping("/{keyId}")
    fun revokeKey(
        request: HttpServletRequest,
        @PathVariable keyId: Int,
        @RequestParam reason: String?
    ): AccountJwk {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return jwkService.revokeKey(account, keyId, reason)
    }
}
