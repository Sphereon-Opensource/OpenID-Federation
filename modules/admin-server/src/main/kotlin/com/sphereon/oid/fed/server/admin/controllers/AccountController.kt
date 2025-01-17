package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.openapi.models.CreateAccountDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.AccountService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/accounts")
class AccountController(
    private val accountService: AccountService
) {
    @GetMapping
    fun getAccounts(): List<AccountDTO> {
        return accountService.getAllAccounts()
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(@RequestBody account: CreateAccountDTO): AccountDTO {
        return accountService.createAccount(account)
    }

    @DeleteMapping
    fun deleteAccount(request: HttpServletRequest): AccountDTO {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return accountService.deleteAccount(account)
    }
}