package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.models.CreateCritDTO
import com.sphereon.oid.fed.persistence.models.Crit
import com.sphereon.oid.fed.services.CritService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts/{accountUsername}/crits")
class CritController {
    private val critService = CritService()

    @PostMapping
    fun createCrit(
        @PathVariable accountUsername: String,
        @RequestBody body: CreateCritDTO
    ): Crit {
        return critService.create(accountUsername, body.claim)
    }

    @GetMapping
    fun getCrits(
        @PathVariable accountUsername: String
    ): Array<Crit> {
        return critService.findByAccountUsername(accountUsername)
    }

    @DeleteMapping("/{id}")
    fun deleteCrit(
        @PathVariable accountUsername: String,
        @PathVariable id: Int
    ): Crit {
        return critService.delete(accountUsername, id)
    }
}
