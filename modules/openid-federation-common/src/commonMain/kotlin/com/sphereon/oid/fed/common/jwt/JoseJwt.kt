package com.sphereon.oid.fed.common.jwt

expect fun sign(payload: String, opts: MutableMap<String, Any>?): String
expect fun verify(jwt: String, key: Any, opts: MutableMap<String, Any>? = mutableMapOf()): Boolean
