package com.sphereon.oid.fed.server.admin.controllers

import com.sphereon.oid.fed.openapi.java.models.CreateReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMark
import com.sphereon.oid.fed.openapi.models.ReceivedTrustMarksResponse
import com.sphereon.oid.fed.server.admin.middlewares.getAccountFromRequest
import com.sphereon.oid.fed.services.ReceivedTrustMarkService
import com.sphereon.oid.fed.services.mappers.toReceivedTrustMarksResponse
import jakarta.servlet.http.HttpServletRequest
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
import com.sphereon.oid.fed.openapi.models.CreateReceivedTrustMark as CreateReceivedTrustMarkKotlin

@RestController
@RequestMapping("/received-trust-marks")
class ReceivedTrustMarkController(
    private val receivedTrustMarkService: ReceivedTrustMarkService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createReceivedTrustMark(
        request: HttpServletRequest,
        @RequestBody receivedTrustMarkData: CreateReceivedTrustMark,
        bindingResult: BindingResult
    ): ResponseEntity<ReceivedTrustMark> {
        if (bindingResult.hasErrors()) {
            throw BindException(bindingResult)
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
            receivedTrustMarkService.createReceivedTrustMark(
                getAccountFromRequest(request),
                CreateReceivedTrustMarkKotlin(
                    trustMarkId = receivedTrustMarkData.trustMarkId,
                    jwt = receivedTrustMarkData.jwt
                )
            )
        )
    }

    @GetMapping
    fun listReceivedTrustMarks(request: HttpServletRequest): ResponseEntity<ReceivedTrustMarksResponse> {
        return receivedTrustMarkService.listReceivedTrustMarks(getAccountFromRequest(request))
            .toReceivedTrustMarksResponse().let {
                ResponseEntity.ok(it)
            }
    }

    @DeleteMapping("/{receivedTrustMarkId}")
    fun deleteReceivedTrustMark(
        request: HttpServletRequest,
        @PathVariable receivedTrustMarkId: String
    ): ResponseEntity<ReceivedTrustMark> {
        return receivedTrustMarkService.deleteReceivedTrustMark(getAccountFromRequest(request), receivedTrustMarkId)
            .let {
                ResponseEntity.ok(it)
            }
    }
}

