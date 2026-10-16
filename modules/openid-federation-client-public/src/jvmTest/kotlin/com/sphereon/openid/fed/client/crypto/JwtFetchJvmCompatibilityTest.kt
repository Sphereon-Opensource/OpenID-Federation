package com.sphereon.openid.fed.client.crypto

import kotlin.test.Test
import kotlin.test.assertTrue

class JwtFetchJvmCompatibilityTest {
    @Test
    fun genericHelperRetainsOriginalSuspendAndDefaultCallDescriptors() {
        val methods = Class.forName("com.sphereon.openid.fed.client.crypto.JwtVerificationKt").declaredMethods
        assertTrue(methods.any { it.name == "fetchAndVerifyJwt" && it.parameterCount == 5 &&
            it.parameterTypes[1] == String::class.java && it.parameterTypes[3].simpleName == "Jwk" &&
            it.parameterTypes[4].name == "kotlin.coroutines.Continuation" })
        assertTrue(methods.any { it.name == "fetchAndVerifyJwt\$default" && it.parameterCount == 7 &&
            it.parameterTypes[5] == Int::class.javaPrimitiveType })
    }
}
