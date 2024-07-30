package com.sphereon.oid.fed.common.httpclient

import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityStatement
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EntityStatementJwtConverter: ContentConverter {

    override suspend fun serializeNullable(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?
    ): OutgoingContent? = when (value) {
        is EntityStatement -> OutgoingEntityStatementContent(value)
        is String -> JsonMapper().mapEntityStatement(value)?.let { OutgoingEntityStatementContent(it) }
        else -> null
    }

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? {
        val text = content.readRemaining().readText(charset)
        return Json.decodeFromString(EntityStatement.serializer(), text)
    }
}

class OutgoingEntityStatementContent(private val entityStatement: EntityStatement): OutgoingContent.ByteArrayContent() {

    override fun bytes(): ByteArray {
        val serializedData = Json.encodeToString(entityStatement)
        return serializedData.toByteArray(Charsets.UTF_8)
    }
}
