package com.sphereon.oid.fed.client.httpclient

import io.ktor.http.*

val EntityStatementJwt get() = io.ktor.http.ContentType("application", "entity-statement+jwt")
