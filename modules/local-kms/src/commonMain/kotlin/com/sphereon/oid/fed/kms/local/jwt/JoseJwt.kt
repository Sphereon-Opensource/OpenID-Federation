package com.sphereon.oid.fed.kms.local.jwt

expect class JwtHeader
expect class JwtPayload

expect fun sign(payload: JwtPayload, header: JwtHeader, opts: Map<String, Any>): String
expect fun verify(jwt: String, key: Any, opts: Map<String, Any>): Boolean
