package com.sphereon.oid.fed.kms.local.jwk

import com.sphereon.oid.fed.openapi.models.JwkWithPrivateKey

expect fun generateKeyPair(): JwkWithPrivateKey
