package com.sphereon.oid.fed.server.admin.mappers

import com.sphereon.oid.fed.openapi.java.models.CreateKey
import com.sphereon.oid.fed.openapi.java.models.SignatureAlgorithm
import com.sphereon.oid.fed.openapi.models.CreateKey as CreateKeyKotlin
import com.sphereon.oid.fed.openapi.models.SignatureAlgorithm as SignatureAlgorithmKotlin


fun CreateKey.toKotlin(): CreateKeyKotlin {
    return CreateKeyKotlin(
        kmsKeyRef = kmsKeyRef,
        signatureAlgorithm = signatureAlgorithm?.toKotlin(),
        kms = kms
    )
}

fun SignatureAlgorithm.toKotlin(): SignatureAlgorithmKotlin {
    return SignatureAlgorithmKotlin.valueOf(name)
}
