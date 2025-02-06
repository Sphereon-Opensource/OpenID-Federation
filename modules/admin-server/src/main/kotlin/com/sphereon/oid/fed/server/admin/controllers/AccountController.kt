package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.Account
import com.sphereon.oid.fed.openapi.models.CreateAccount
import com.sphereon.oid.fed.services.AccountService
import com.sphereon.oid.fed.services.mappers.toDTO
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/accounts")
class AccountController(
    private val accountService: AccountService
) {
    @GetMapping
    fun getAccounts(): List<Account> {
        return accountService.getAllAccounts().map { it.toDTO() }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(@RequestBody account: CreateAccount): Account {
        return accountService.createAccount(account).toDTO()
    }

    @DeleteMapping
    fun deleteAccount(request: HttpServletRequest): Account {
        val account = request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account
        return accountService.deleteAccount(account).toDTO()
    }
}