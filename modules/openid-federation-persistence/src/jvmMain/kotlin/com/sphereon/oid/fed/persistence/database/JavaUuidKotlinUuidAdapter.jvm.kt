package com.sphereon.oid.fed.persistence.database

import app.cash.sqldelight.ColumnAdapter
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid


@OptIn(ExperimentalUuidApi::class)
public data object JavaUuidKotlinUuidAdapter : ColumnAdapter<Uuid, UUID> {
    override fun decode(databaseValue: UUID): Uuid = databaseValue.toKotlinUuid()
    override fun encode(value: Uuid): UUID = value.toJavaUuid()
}