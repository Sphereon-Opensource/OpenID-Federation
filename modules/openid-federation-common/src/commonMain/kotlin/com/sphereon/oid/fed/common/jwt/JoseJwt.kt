package com.sphereon.oid.fed.common.jwt

expect fun sign(payload: String, opts: Map<String, Any>): String
expect fun verify(jwt: String, key: Any, opts: Map<String, Any>): Boolean
