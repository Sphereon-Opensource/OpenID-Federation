package com.sphereon.oid.fed.kms.local

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table


@Table("keys")
data class Key(
    @Id val id: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val algorithm: String
)
