package com.sphereon.oid.fed.client.crypto

import com.sphereon.oid.fed.openapi.models.Jwk
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

@JsModule("jose")
@JsNonModule
external object Jose {
    fun importJWK(jwk: Jwk, alg: String, options: dynamic = definedExternally): Promise<dynamic>
    fun jwtVerify(jwt: String, key: Any, options: dynamic): Promise<Boolean>
}

class CryptoTest {
    private val cryptoService = CryptoServiceJS.register(CryptoPlatformCallback())

    @Test
    fun testVerifyValidJwt() = runTest {
        val jwt =
            "eyJraWQiOiJkZWZhdWx0UlNBU2lnbiIsInR5cCI6ImVudGl0eS1zdGF0ZW1lbnQrand0IiwiYWxnIjoiUlMyNTYifQ.eyJzdWIiOiJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCIsIm1ldGFkYXRhIjp7ImZlZGVyYXRpb25fZW50aXR5Ijp7ImZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQiOiJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9mZXRjaCIsImZlZGVyYXRpb25fcmVzb2x2ZV9lbmRwb2ludCI6Imh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0L3Jlc29sdmUiLCJmZWRlcmF0aW9uX3RydXN0X21hcmtfc3RhdHVzX2VuZHBvaW50IjoiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvdHJ1c3RfbWFya19zdGF0dXMiLCJmZWRlcmF0aW9uX2xpc3RfZW5kcG9pbnQiOiJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9saXN0In19LCJqd2tzIjp7ImtleXMiOlt7Imt0eSI6IlJTQSIsImUiOiJBUUFCIiwidXNlIjoic2lnIiwia2lkIjoiZGVmYXVsdFJTQVNpZ24iLCJuIjoicVJUSkhRZ2IyZjhjbG45ZEpiLVdnaWs0cUVMNUdHX19zUHpsQVU0aTY5UzZ5SHhlTWczMllnTGZVenBOQnhfOGtYMm5kellYTV9SS21vM2poalF4dXhDSzFJSFNRY01rZzFoR2lpLXhSdzh4NDV0OFNHbFdjU0hpN182UmFBWTFTeUZjRUVsTkFxSGk1b2VCYUIzRkd2ZnJWLUVQLWNOa1V2R0VWYnlzX0NieHlHRFE5UU0wTkVyc2lsVmxNQVJERXJFTlpjclkwck5LdDUyV29aZ3kzcHNWY2Q4VTVEMExxZkM3N2JQakczNVBhVmh3WUFubFAwZXowSGY2dHV5V0pIZUE1MmRDZGUtbmEzV2ptUGFya2NscEZyLUtqWGVJQzhCd2ZqRXBBWGJLY3A4Tm11UUZqOWZEOUtuUjZ2Q2RPOTFSeUJJYkRsdUw1TEg4czBxRENRIn0seyJrdHkiOiJFQyIsInVzZSI6InNpZyIsImNydiI6IlAtMjU2Iiwia2lkIjoiZGVmYXVsdEVDU2lnbiIsIngiOiJ4TWtXSWExRVp5amdtazNKUUx0SERBOXAwVHBQOXdNU2JKSzBvQWl0Z2NrIiwieSI6IkNWTEZzdE93S3d0UXJ1dF92b0hqWU82SnoxSzBOWFJ1OE9MQ1RtS29zTGcifSx7Imt0eSI6IlJTQSIsImUiOiJBUUFCIiwidXNlIjoiZW5jIiwia2lkIjoiZGVmYXVsdFJTQUVuYyIsIm4iOiJ3ZXcyMnhjcGZBU2tRUXA3U09vX0dzNmNLajJYeTd4VlpLX3RnWnh6QXlReExTeG01c1U0WkdzNm1kSUFIZEV2UTkxU25FSFR0anBlQVM5d0N2TlhWbVZ4TklqRkFQSnpDWXBzZkZ4R3pXMVBSM1NDQmVLUFl6VWpTeUJTZWw1LW1Td1U4MHlZQXFPbFoxUVJaTlFJNUVTVXZOUG9lUEZqR0NvZnhuRlJzbXF5X21Bd1p5bmQyTnJyc1QyQXlwMEw2UFF3ei1Fa09oakVCcHpzeXEwcE11am5aRWZ2UHk5UC1YdjJTVUZMZUpQcm1jRHllNjRaMlk5V1BoMmpwa25oT3hESzhSTUwtMllUdmI0dVNPalowWFpPVzltVm9nTkpSSm0yemVQVGVlTFBxR2x1TGNEenBsYnkwbkxiTGpkWDdLM29MYnFoRGFld2o3VnJhS2Vtc1EifV19LCJ0cnVzdF9tYXJrX2lzc3VlcnMiOnsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb2F1dGhfcmVzb3VyY2UvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcHJvdmlkZXIvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vYXV0aF9yZXNvdXJjZS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiLCJodHRwczovL2NvaGVzaW9uMi5yZWdpb25lLm1hcmNoZS5pdC9vaWRjL3NhLyIsImh0dHBzOi8vYXV0aC50b3NjYW5hLml0L2F1dGgvcmVhbG1zL2VudGkvZmVkZXJhdGlvbi1lbnRpdHkvcl90b3NjYW5fc2FfZW50aSIsImh0dHBzOi8vYXV0ZW50aWNhemlvbmUuY2xvdWQucHJvdmluY2lhLnRuLml0L2FnZ3JlZ2F0b3JlIiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3ByaXZhdGUiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb3BlbmlkX3Byb3ZpZGVyL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wcml2YXRlIjpbImh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0IiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXX0sImlzcyI6Imh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0IiwiZXhwIjoxNzI5MTAzMjQxLCJpYXQiOjE3MjkwMTY4NDEsImNvbnN0cmFpbnRzIjp7Im1heF9wYXRoX2xlbmd0aCI6MX0sInRydXN0X21hcmtzX2lzc3VlcnMiOnsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb2F1dGhfcmVzb3VyY2UvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcHJvdmlkZXIvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vYXV0aF9yZXNvdXJjZS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiLCJodHRwczovL2NvaGVzaW9uMi5yZWdpb25lLm1hcmNoZS5pdC9vaWRjL3NhLyIsImh0dHBzOi8vYXV0aC50b3NjYW5hLml0L2F1dGgvcmVhbG1zL2VudGkvZmVkZXJhdGlvbi1lbnRpdHkvcl90b3NjYW5fc2FfZW50aSIsImh0dHBzOi8vYXV0ZW50aWNhemlvbmUuY2xvdWQucHJvdmluY2lhLnRuLml0L2FnZ3JlZ2F0b3JlIiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3ByaXZhdGUiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb3BlbmlkX3Byb3ZpZGVyL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wcml2YXRlIjpbImh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0IiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXX19.j9bmJRlLokkmWTpPkrphtB5dyVrKQPwY7U9jtl_PXlgVODmDXla0vbQszR_b0aUfk7j-Sh5v_UwtHRF6P5vPcaTvUiaPcbtFEIVq0xW9xcyjgPmYEkfHyB9CWxfq-AC6OOoRunGyTOO5G9xdup6QSLFxLQBlMZh5sE_X8wzkG02dZOfl8RTzuoquzNMl-yWpyb0Rxk_iY-ZhGa1yDPHm16tFmXMY3sf0QOBQAAGxBaRhcjekRnXPEijrPIaV381_VnQdd4xtbikI_XNRiGeyuoMii40K4l6qiznZ-_mz8GaRdS21Dc5XL5cjwMc4EDGxSNnW9NgBr7R4HDURyiixcA"

        val deferred = async {
            val result = cryptoService.verify(jwt).await()
            assertEquals(true, result)
        }

        deferred.await()
    }

    @Test
    fun testVerifyValidJwtExpired() = runTest {
        val jwt =
            "eyJraWQiOiJkZWZhdWx0UlNBU2lnbiIsInR5cCI6ImVudGl0eS1zdGF0ZW1lbnQrand0IiwiYWxnIjoiUlMyNTYifQ.eyJzdWIiOiJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCIsIm1ldGFkYXRhIjp7ImZlZGVyYXRpb25fZW50aXR5Ijp7ImZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQiOiJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9mZXRjaCIsImZlZGVyYXRpb25fcmVzb2x2ZV9lbmRwb2ludCI6Imh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0L3Jlc29sdmUiLCJmZWRlcmF0aW9uX3RydXN0X21hcmtfc3RhdHVzX2VuZHBvaW50IjoiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvdHJ1c3RfbWFya19zdGF0dXMiLCJmZWRlcmF0aW9uX2xpc3RfZW5kcG9pbnQiOiJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9saXN0In19LCJqd2tzIjp7ImtleXMiOlt7Imt0eSI6IlJTQSIsImUiOiJBUUFCIiwidXNlIjoic2lnIiwia2lkIjoiZGVmYXVsdFJTQVNpZ24iLCJuIjoicVJUSkhRZ2IyZjhjbG45ZEpiLVdnaWs0cUVMNUdHX19zUHpsQVU0aTY5UzZ5SHhlTWczMllnTGZVenBOQnhfOGtYMm5kellYTV9SS21vM2poalF4dXhDSzFJSFNRY01rZzFoR2lpLXhSdzh4NDV0OFNHbFdjU0hpN182UmFBWTFTeUZjRUVsTkFxSGk1b2VCYUIzRkd2ZnJWLUVQLWNOa1V2R0VWYnlzX0NieHlHRFE5UU0wTkVyc2lsVmxNQVJERXJFTlpjclkwck5LdDUyV29aZ3kzcHNWY2Q4VTVEMExxZkM3N2JQakczNVBhVmh3WUFubFAwZXowSGY2dHV5V0pIZUE1MmRDZGUtbmEzV2ptUGFya2NscEZyLUtqWGVJQzhCd2ZqRXBBWGJLY3A4Tm11UUZqOWZEOUtuUjZ2Q2RPOTFSeUJJYkRsdUw1TEg4czBxRENRIn0seyJrdHkiOiJFQyIsInVzZSI6InNpZyIsImNydiI6IlAtMjU2Iiwia2lkIjoiZGVmYXVsdEVDU2lnbiIsIngiOiJ4TWtXSWExRVp5amdtazNKUUx0SERBOXAwVHBQOXdNU2JKSzBvQWl0Z2NrIiwieSI6IkNWTEZzdE93S3d0UXJ1dF92b0hqWU82SnoxSzBOWFJ1OE9MQ1RtS29zTGcifSx7Imt0eSI6IlJTQSIsImUiOiJBUUFCIiwidXNlIjoiZW5jIiwia2lkIjoiZGVmYXVsdFJTQUVuYyIsIm4iOiJ3ZXcyMnhjcGZBU2tRUXA3U09vX0dzNmNLajJYeTd4VlpLX3RnWnh6QXlReExTeG01c1U0WkdzNm1kSUFIZEV2UTkxU25FSFR0anBlQVM5d0N2TlhWbVZ4TklqRkFQSnpDWXBzZkZ4R3pXMVBSM1NDQmVLUFl6VWpTeUJTZWw1LW1Td1U4MHlZQXFPbFoxUVJaTlFJNUVTVXZOUG9lUEZqR0NvZnhuRlJzbXF5X21Bd1p5bmQyTnJyc1QyQXlwMEw2UFF3ei1Fa09oakVCcHpzeXEwcE11am5aRWZ2UHk5UC1YdjJTVUZMZUpQcm1jRHllNjRaMlk5V1BoMmpwa25oT3hESzhSTUwtMllUdmI0dVNPalowWFpPVzltVm9nTkpSSm0yemVQVGVlTFBxR2x1TGNEenBsYnkwbkxiTGpkWDdLM29MYnFoRGFld2o3VnJhS2Vtc1EifV19LCJ0cnVzdF9tYXJrX2lzc3VlcnMiOnsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb2F1dGhfcmVzb3VyY2UvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcHJvdmlkZXIvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vYXV0aF9yZXNvdXJjZS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiLCJodHRwczovL2NvaGVzaW9uMi5yZWdpb25lLm1hcmNoZS5pdC9vaWRjL3NhLyIsImh0dHBzOi8vYXV0aC50b3NjYW5hLml0L2F1dGgvcmVhbG1zL2VudGkvZmVkZXJhdGlvbi1lbnRpdHkvcl90b3NjYW5fc2FfZW50aSIsImh0dHBzOi8vYXV0ZW50aWNhemlvbmUuY2xvdWQucHJvdmluY2lhLnRuLml0L2FnZ3JlZ2F0b3JlIiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3ByaXZhdGUiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb3BlbmlkX3Byb3ZpZGVyL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wcml2YXRlIjpbImh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0IiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXX0sImlzcyI6Imh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0IiwiZXhwIjoxNzI4NDI4MDI1LCJpYXQiOjE3MjgzNDE2MjUsImNvbnN0cmFpbnRzIjp7Im1heF9wYXRoX2xlbmd0aCI6MX0sInRydXN0X21hcmtzX2lzc3VlcnMiOnsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb2F1dGhfcmVzb3VyY2UvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcHJvdmlkZXIvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vYXV0aF9yZXNvdXJjZS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiLCJodHRwczovL2NvaGVzaW9uMi5yZWdpb25lLm1hcmNoZS5pdC9vaWRjL3NhLyIsImh0dHBzOi8vYXV0aC50b3NjYW5hLml0L2F1dGgvcmVhbG1zL2VudGkvZmVkZXJhdGlvbi1lbnRpdHkvcl90b3NjYW5fc2FfZW50aSIsImh0dHBzOi8vYXV0ZW50aWNhemlvbmUuY2xvdWQucHJvdmluY2lhLnRuLml0L2FnZ3JlZ2F0b3JlIiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3ByaXZhdGUiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb3BlbmlkX3Byb3ZpZGVyL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wcml2YXRlIjpbImh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0IiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXX19.QVndoAzYG4-r-f1mq2szTurjN4IWG5GN6aUBeIm6k5EXOdjEa2oOmP8iANBjCFWF6eNPNN2t342pBpb6-46o9kJv9MxyWASIaBkOv_X8RJGEgv2ghDLLnfOLv4R6J9XH9IIsQPzjlezgWJYk61ukfYN7kWA_aIT5Hf42zEU14V5kLbl50r8wjgJVRwmSBsDLKsWbOnbzfkiKv4druFhfhDZjiyBeCjYajh9MFYdAR1awYihNM-JVib89Z7XgOqxq4qGogPt_XU-YMuf917lw4kpphPRoUe1QIoj1KXfgbpJUdgiLMlXQoBl57Ej3b1mVWgEkC6oKjNyNvZR57Kx8AQ"

        val deferred = async {
            val result = cryptoService.verify(jwt).await()
            assertEquals(false, result)
        }

        deferred.await()
    }

    @Test
    fun testVerifyJwtWithoutKeys() = runTest {
        val jwt =
            "eyJraWQiOiJCNkVCODQ4OENDODRDNDEwMTcxMzRCQzc3RjQxMzJBMDQ2N0NDQzBFIiwidHlwIjoiZW50aXR5LXN0YXRlbWVudFx1MDAyQmp3dCIsImFsZyI6IlJTMjU2In0.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWUsImV4cCI6MTUxNjIzOTAyMn0.NHVaYe26MbtOYhSKkoKYdFVomg4i8ZJd8_-RU8VNb"
        val deferred = async {
            val result = cryptoService.verify(jwt).await()
            assertEquals(false, result)
        }

        deferred.await()
    }

    @Test
    fun testVerifyInvalidSignature() = runTest {
        val jwt =
            "eyJraWQiOiJkZWZhdWx0UlNBU2lnbiIsInR5cCI6ImVudGl0eS1zdGF0ZW1lbnQrand0IiwiYWxnIjoiUlMyNTYifQ.eyJzdWIiOiJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCIsIm1ldGFkYXRhIjp7ImZlZGVyYXRpb25fZW50aXR5Ijp7ImZlZGVyYXRpb25fZmV0Y2hfZW5kcG9pbnQiOiJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9mZXRjaCIsImZlZGVyYXRpb25fcmVzb2x2ZV9lbmRwb2ludCI6Imh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0L3Jlc29sdmUiLCJmZWRlcmF0aW9uX3RydXN0X21hcmtfc3RhdHVzX2VuZHBvaW50IjoiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvdHJ1c3RfbWFya19zdGF0dXMiLCJmZWRlcmF0aW9uX2xpc3RfZW5kcG9pbnQiOiJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9saXN0In19LCJqd2tzIjp7ImtleXMiOlt7Imt0eSI6IlJTQSIsImUiOiJBUUFCIiwidXNlIjoic2lnIiwia2lkIjoiZGVmYXVsdFJTQVNpZ24iLCJuIjoicVJUSkhRZ2IyZjhjbG45ZEpiLVdnaWs0cUVMNUdHX19zUHpsQVU0aTY5UzZ5SHhlTWczMllnTGZVenBOQnhfOGtYMm5kellYTV9SS21vM2poalF4dXhDSzFJSFNRY01rZzFoR2lpLXhSdzh4NDV0OFNHbFdjU0hpN182UmFBWTFTeUZjRUVsTkFxSGk1b2VCYUIzRkd2ZnJWLUVQLWNOa1V2R0VWYnlzX0NieHlHRFE5UU0wTkVyc2lsVmxNQVJERXJFTlpjclkwck5LdDUyV29aZ3kzcHNWY2Q4VTVEMExxZkM3N2JQakczNVBhVmh3WUFubFAwZXowSGY2dHV5V0pIZUE1MmRDZGUtbmEzV2ptUGFya2NscEZyLUtqWGVJQzhCd2ZqRXBBWGJLY3A4Tm11UUZqOWZEOUtuUjZ2Q2RPOTFSeUJJYkRsdUw1TEg4czBxRENRIn0seyJrdHkiOiJFQyIsInVzZSI6InNpZyIsImNydiI6IlAtMjU2Iiwia2lkIjoiZGVmYXVsdEVDU2lnbiIsIngiOiJ4TWtXSWExRVp5amdtazNKUUx0SERBOXAwVHBQOXdNU2JKSzBvQWl0Z2NrIiwieSI6IkNWTEZzdE93S3d0UXJ1dF92b0hqWU82SnoxSzBOWFJ1OE9MQ1RtS29zTGcifSx7Imt0eSI6IlJTQSIsImUiOiJBUUFCIiwidXNlIjoiZW5jIiwia2lkIjoiZGVmYXVsdFJTQUVuYyIsIm4iOiJ3ZXcyMnhjcGZBU2tRUXA3U09vX0dzNmNLajJYeTd4VlpLX3RnWnh6QXlReExTeG01c1U0WkdzNm1kSUFIZEV2UTkxU25FSFR0anBlQVM5d0N2TlhWbVZ4TklqRkFQSnpDWXBzZkZ4R3pXMVBSM1NDQmVLUFl6VWpTeUJTZWw1LW1Td1U4MHlZQXFPbFoxUVJaTlFJNUVTVXZOUG9lUEZqR0NvZnhuRlJzbXF5X21Bd1p5bmQyTnJyc1QyQXlwMEw2UFF3ei1Fa09oakVCcHpzeXEwcE11am5aRWZ2UHk5UC1YdjJTVUZMZUpQcm1jRHllNjRaMlk5V1BoMmpwa25oT3hESzhSTUwtMllUdmI0dVNPalowWFpPVzltVm9nTkpSSm0yemVQVGVlTFBxR2x1TGNEenBsYnkwbkxiTGpkWDdLM29MYnFoRGFld2o3VnJhS2Vtc1EifV19LCJ0cnVzdF9tYXJrX2lzc3VlcnMiOnsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb2F1dGhfcmVzb3VyY2UvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcHJvdmlkZXIvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vYXV0aF9yZXNvdXJjZS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiLCJodHRwczovL2NvaGVzaW9uMi5yZWdpb25lLm1hcmNoZS5pdC9vaWRjL3NhLyIsImh0dHBzOi8vYXV0aC50b3NjYW5hLml0L2F1dGgvcmVhbG1zL2VudGkvZmVkZXJhdGlvbi1lbnRpdHkvcl90b3NjYW5fc2FfZW50aSIsImh0dHBzOi8vYXV0ZW50aWNhemlvbmUuY2xvdWQucHJvdmluY2lhLnRuLml0L2FnZ3JlZ2F0b3JlIiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3ByaXZhdGUiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb3BlbmlkX3Byb3ZpZGVyL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wcml2YXRlIjpbImh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0IiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXX0sImlzcyI6Imh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0IiwiZXhwIjoxNzI5MTAzMjQxLCJpYXQiOjE3MjkwMTY4NDEsImNvbnN0cmFpbnRzIjp7Im1heF9wYXRoX2xlbmd0aCI6MX0sInRydXN0X21hcmtzX2lzc3VlcnMiOnsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb2F1dGhfcmVzb3VyY2UvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcHJvdmlkZXIvcHJpdmF0ZSI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vYXV0aF9yZXNvdXJjZS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wdWJsaWMiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiLCJodHRwczovL2NvaGVzaW9uMi5yZWdpb25lLm1hcmNoZS5pdC9vaWRjL3NhLyIsImh0dHBzOi8vYXV0aC50b3NjYW5hLml0L2F1dGgvcmVhbG1zL2VudGkvZmVkZXJhdGlvbi1lbnRpdHkvcl90b3NjYW5fc2FfZW50aSIsImh0dHBzOi8vYXV0ZW50aWNhemlvbmUuY2xvdWQucHJvdmluY2lhLnRuLml0L2FnZ3JlZ2F0b3JlIiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvaW50ZXJtZWRpYXRlL3ByaXZhdGUiOlsiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQiXSwiaHR0cHM6Ly9vaWRjLnJlZ2lzdHJ5LnNlcnZpemljaWUuaW50ZXJuby5nb3YuaXQvb3BlbmlkX3Byb3ZpZGVyL3B1YmxpYyI6WyJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdCJdLCJodHRwczovL29pZGMucmVnaXN0cnkuc2Vydml6aWNpZS5pbnRlcm5vLmdvdi5pdC9vcGVuaWRfcmVseWluZ19wYXJ0eS9wcml2YXRlIjpbImh0dHBzOi8vb2lkYy5yZWdpc3RyeS5zZXJ2aXppY2llLmludGVybm8uZ292Lml0IiwiaHR0cHM6Ly9vaWRjc2Eud2VibG9vbS5pdCIsImh0dHBzOi8vc3BpZC53YnNzLml0L1NwaWQvb2lkYy9zYSIsImh0dHBzOi8vc2VjdXJlLmVyZW1pbmQuaXQvaWRlbnRpdGEtZGlnaXRhbGUtb2lkYy9vaWRjLWZlZCIsImh0dHBzOi8vY2llLW9pZGMuY29tdW5lLW9ubGluZS5pdC9BdXRoU2VydmljZU9JREMvb2lkYy9zYSIsImh0dHBzOi8vcGhwLWNpZS5hbmR4b3IuaXQiLCJodHRwczovL2xvZ2luLmFzZndlYi5pdC8iLCJodHRwczovL29pZGMuc3R1ZGlvYW1pY2EuY29tIiwiaHR0cHM6Ly9pZHAuZW50cmFuZXh0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9jd29sc3NvLm51dm9sYXBhbGl0YWxzb2Z0Lml0L3NlcnZpY2VzL29pZGMvc2Evc3NvIiwiaHR0cHM6Ly9mZWRlcmEubGVwaWRhLml0L2d3L09pZGNTYUZ1bGwvIiwiaHR0cHM6Ly93d3cuZXVyb2NvbnRhYi5pdC9hcGkiXX19.j9bmJRlLokkmWTpPkrphtB5dyVrKQPwY7U9jtl_PXlgVODmDXla0vbQszR_b0aUfk7j-Sh5v_UwtHRF6P5vPcaTvUiaPcbtFEIVq0xW9xcyjgPmYEkfHyB9CWxfq-AC6OOoRunGyTOO5G9xdup6QSLFxLQBlMZh5sE_X8wzkG02dZOfl8RTzuoquzNMl-yWpyb0Rxk_iY-ZhGa1yDPHm16tFmXMY3sf0QOBQAAGxBaRhcjekRnXPEijrPIaV381_VnQdd4xtbikI_XNRiGeyuoMii40K4l6qiznZ-_mz8GaRdS21Dc5XL5cjwMc4EDGxSNnW9NgBr7R4HDURyii"
        val deferred = async {
            val result = cryptoService.verify(jwt).await()
            assertEquals(false, result)
        }

        deferred.await()
    }
}
