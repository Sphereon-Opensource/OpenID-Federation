package com.sphereon.oid.fed.common.httpclient

import com.sphereon.oid.fed.common.mapper.JsonMapper
import com.sphereon.oid.fed.openapi.models.EntityConfigurationStatement
import com.sphereon.oid.fed.openapi.models.SubordinateStatement
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

class EntityConfigurationStatementJwtConverter : ContentConverter {

    override suspend fun serializeNullable(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?
    ): OutgoingContent? = when (value) {
        is EntityConfigurationStatement -> OutgoingEntityStatementContent(value)
        is SubordinateStatement -> OutgoingSubordinateStatementContent(value)
        is String -> JsonMapper().mapEntityStatement(value)?.let { OutgoingEntityStatementContent(it) }
        else -> null
    }

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any =
        content.readRemaining().readText(charset).let {
            Json.decodeFromString(serializer(typeInfo.type), it)
        }

    @OptIn(InternalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun <T> serializer(type: KClass<*>): KSerializer<T> {
        return type.serializer() as KSerializer<T>
    }
}

class OutgoingEntityStatementContent(private val entityStatement: EntityConfigurationStatement) :
    OutgoingContent.ByteArrayContent() {

    override fun bytes(): ByteArray =
        Json.encodeToString(entityStatement).toByteArray(Charsets.UTF_8)
}

class OutgoingSubordinateStatementContent(private val entityStatement: SubordinateStatement) :
    OutgoingContent.ByteArrayContent() {

    override fun bytes(): ByteArray =
        Json.encodeToString(entityStatement).toByteArray(Charsets.UTF_8)
}
