package com.sphereon.oid.fed.client

import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*

actual fun getHttpEngine(): HttpClientEngine {
    return CIO.create()
}
