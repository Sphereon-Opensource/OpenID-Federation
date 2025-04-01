package com.sphereon.oid.fed.server.admin.helpers

import java.util.UUID

fun String.toUUID() = UUID.fromString(this)