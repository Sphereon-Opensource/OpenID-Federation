package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.mappers.account.toAccountsResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/accounts")
class AccountController(
    private val accountService: AccountService
) {
    @GetMapping
    fun getAccounts() = accountService.getAllAccounts().toAccountsResponse()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(@RequestBody account: CreateAccount) = accountService.createAccount(account)

    @DeleteMapping
    fun deleteAccount(request: HttpServletRequest): Account {
        val account = getAccountFromRequest(request)
        return accountService.deleteAccount(account)
    }
}