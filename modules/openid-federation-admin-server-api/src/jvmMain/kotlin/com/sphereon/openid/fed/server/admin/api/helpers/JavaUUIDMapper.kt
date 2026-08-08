package com.sphereon.openid.fed.server.admin.api.helpers

import java.util.UUID

fun String.toUUID(): UUID = UUID.fromString(this)
