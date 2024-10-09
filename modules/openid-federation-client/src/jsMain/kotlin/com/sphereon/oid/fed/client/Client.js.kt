package com.sphereon.oid.fed.client

import io.ktor.client.engine.*
import io.ktor.client.engine.js.*

actual fun getHttpEngine(): HttpClientEngine {
    return Js.create()
}
