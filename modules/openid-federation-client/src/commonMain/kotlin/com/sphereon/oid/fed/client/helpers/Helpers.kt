package com.sphereon.oid.fed.client.helpers

fun getEntityConfigurationEndpoint(iss: String): String {
    val sb = StringBuilder()
    sb.append(iss.trim('"'))
    sb.append("/.well-known/openid-federation")
    return sb.toString()
}

fun getSubordinateStatementEndpoint(fetchEndpoint: String, sub: String): String {
    val sb = StringBuilder()
    sb.append(fetchEndpoint.trim('"'))
    sb.append("?sub=")
    sb.append(sub)
    return sb.toString()
}
