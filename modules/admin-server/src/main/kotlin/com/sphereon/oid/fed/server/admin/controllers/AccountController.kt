package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.common.Constants
import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.openapi.models.CreateAccountDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.AccountService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

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
        return accountService.deleteAccount(request.getAttribute(Constants.ACCOUNT_ATTRIBUTE) as Account)
    }
}
