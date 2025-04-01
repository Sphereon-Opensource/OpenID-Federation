package com.sphereon.oid.fed.persistence.database

import app.cash.sqldelight.ColumnAdapter
import java.util.*
import kotlin.uuid.ExperimentalUuidApi


@OptIn(ExperimentalUuidApi::class)
object JavaUuidStringAdapter : ColumnAdapter<String, UUID> {
    override fun decode(databaseValue: UUID): String = databaseValue.toString()
    override fun encode(value: String): UUID = UUID.fromString(value)
}