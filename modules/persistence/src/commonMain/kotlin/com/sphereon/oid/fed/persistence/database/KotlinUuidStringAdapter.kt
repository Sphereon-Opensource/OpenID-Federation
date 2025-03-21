package com.sphereon.oid.fed.persistence.database

import app.cash.sqldelight.ColumnAdapter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid


@OptIn(ExperimentalUuidApi::class)
public data object KotlinUuidStringAdapter : ColumnAdapter<Uuid, String> {
    override fun decode(databaseValue: String): Uuid = Uuid.parse(databaseValue)
    override fun encode(value: Uuid): String = value.toString()
}