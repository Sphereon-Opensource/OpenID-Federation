package com.sphereon.oid.fed.client.helpers

fun getEntityConfigurationEndpoint(iss: String): String {
    return "${if (iss.endsWith("/")) iss.dropLast(1) else iss}/.well-known/openid-federation"
}

fun getSubordinateStatementEndpoint(fetchEndpoint: String, sub: String): String {
    return "${fetchEndpoint}?sub=$sub"
}
