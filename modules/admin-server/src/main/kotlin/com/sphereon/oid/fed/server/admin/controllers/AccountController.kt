package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.AccountDTO
import com.sphereon.oid.fed.openapi.models.CreateAccountDTO
import com.sphereon.oid.fed.persistence.models.Account
import com.sphereon.oid.fed.services.AccountService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts")
class AccountController {
    private val accountService = AccountService()

    @GetMapping
    fun getAccounts(): List<AccountDTO> {
        return accountService.findAll()
    }

    @PostMapping
    fun createAccount(@RequestBody account: CreateAccountDTO): AccountDTO {
        return accountService.create(account)
    }

    @DeleteMapping("/{accountUsername}")
    fun deleteAccount(@PathVariable accountUsername: String): Account {
        return accountService.deleteAccount(accountUsername)
    }
}
