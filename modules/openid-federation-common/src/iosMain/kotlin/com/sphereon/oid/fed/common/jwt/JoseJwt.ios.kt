package com.sphereon.oid.fed.common.jwt

actual fun sign(
    payload: String,
    opts: MutableMap<String, Any>?
): String {
    TODO("Not yet implemented")
}

actual fun verify(
    jwt: String,
    key: Any,
    opts: MutableMap<String, Any>?
): Boolean {
    TODO("Not yet implemented")
}