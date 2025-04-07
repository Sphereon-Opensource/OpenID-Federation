package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.java.models.CreateTrustMarkRequest
import com.sphereon.oid.fed.openapi.models.TrustMark
import com.sphereon.oid.fed.openapi.models.TrustMarksResponse
import com.sphereon.oid.fed.server.admin.mappers.toKotlin
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.TrustMarkService
import com.sphereon.oid.fed.services.mappers.toTrustMarksResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/trust-marks")
class TrustMarkController(
    private val trustMarkService: TrustMarkService
) {

    @GetMapping
    fun getTrustMarks(request: HttpServletRequest): ResponseEntity<TrustMarksResponse> {
        return ResponseEntity.ok(
            trustMarkService.getTrustMarksForAccount(getAccountFromRequest(request)).toTrustMarksResponse()
        )
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createTrustMark(
        request: HttpServletRequest,
        @Valid @RequestBody body: CreateTrustMarkRequest,
        bindingResult: BindingResult
    ): ResponseEntity<String> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }
        val trustMark =
            runBlocking { trustMarkService.createTrustMark(getAccountFromRequest(request), body.toKotlin()) }

        if (body.dryRun == true) {
            return ResponseEntity.ok(trustMark)
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(trustMark)
    }

    @DeleteMapping("/{trustMarkId}")
    fun deleteTrustMark(
        request: HttpServletRequest,
        @PathVariable trustMarkId: String
    ): ResponseEntity<TrustMark> {
        return ResponseEntity.ok(trustMarkService.deleteTrustMark(getAccountFromRequest(request), trustMarkId))
    }
}
