package com.sphereon.openid.fed.httpResolver

import kotlin.test.Test
import kotlin.test.assertTrue

class TypedJwtJvmCompatibilityTest {
    @Test
    fun entityStatementHelperRetainsOriginalSuspendAndDefaultCallDescriptors() {
        val methods = HttpResolver::class.java.declaredMethods
        assertTrue(methods.any { it.name == "getJwt" && it.parameterCount == 3 &&
            it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
            it.parameterTypes[2].name == "kotlin.coroutines.Continuation" })
        assertTrue(methods.any { it.name == "getJwt\$default" && it.parameterCount == 6 &&
            it.parameterTypes[4] == Int::class.javaPrimitiveType })
    }
}
