package com.sphereon.oid.fed.server.services

import com.sphereon.oid.fed.common.exceptions.federation.NotFoundException
import com.sphereon.oid.fed.common.exceptions.federation.ServerErrorException
import com.sphereon.oid.fed.services.SubordinateService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service

@Service
class FetchService(private val subordinateService: SubordinateService) {
    fun fetchSubordinateStatement(accountIss: String, sub: String): ResponseEntity<String> {
        try {
            val subordinateStatement = subordinateService.fetchSubordinateStatement(accountIss, sub)
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/entity-statement+jwt"))
                .body(subordinateStatement)
        } catch (e: com.sphereon.oid.fed.common.exceptions.admin.NotFoundException) {
            throw NotFoundException(e.message ?: "Subordinate statement not found")
        } catch (e: Exception) {
            throw ServerErrorException(e.message ?: "Error while fetching subordinate statement")
        }
    }
}
