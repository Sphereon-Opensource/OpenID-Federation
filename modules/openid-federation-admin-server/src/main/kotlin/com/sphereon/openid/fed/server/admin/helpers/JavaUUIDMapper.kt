package com.sphereon.openid.fed.server.admin.helpers

import java.util.UUID

fun String.toUUID() = UUID.fromString(this)