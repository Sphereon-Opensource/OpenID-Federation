package com.sphereon.oid.fed.client

import com.sphereon.oid.fed.client.fetch.Fetch
import com.sphereon.oid.fed.client.mapper.JsonMapper
import com.sphereon.oid.fed.client.trustchain.TrustChain
import io.ktor.client.engine.*

expect fun getHttpEngine(): HttpClientEngine

class Client(engine: HttpClientEngine? = null) {

    private val httpClientEngine: HttpClientEngine by lazy {
        engine ?: getHttpEngine()
    }

    val fetch: Fetch by lazy {
        Fetch(httpClientEngine)
    }

    val jsonMapper: JsonMapper by lazy {
        JsonMapper()
    }

    val trustChain: TrustChain by lazy {
        TrustChain(httpClientEngine)
    }
}
