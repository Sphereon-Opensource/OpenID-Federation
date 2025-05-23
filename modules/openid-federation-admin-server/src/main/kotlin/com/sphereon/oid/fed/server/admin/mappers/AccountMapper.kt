package com.sphereon.oid.fed.server.admin.mappers

import com.sphereon.oid.fed.openapi.java.models.CreateAccount
import com.sphereon.oid.fed.openapi.models.CreateAccount as CreateAccountKotlin

fun CreateAccount.toKotlin(): CreateAccountKotlin {
    return CreateAccountKotlin(
        identifier = identifier,
        username = username,
    )
}
