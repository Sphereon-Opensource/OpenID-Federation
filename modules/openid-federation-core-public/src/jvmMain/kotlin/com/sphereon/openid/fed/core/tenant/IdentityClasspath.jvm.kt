package com.sphereon.openid.fed.core.tenant

/**
 * JVM: probe for account-http Metro contribution class (present only when that jar is a dependency).
 */
actual object IdentityClasspath {
    actual fun accountModulesPresent(): Boolean {
        return classPresent("com.sphereon.openid.fed.account.http.di.AccountAdminEndpointContributionImpl") ||
            classPresent("com.sphereon.openid.fed.account.http.di.AccountAdminContributionsKt") ||
            // Package-level marker used by some builds
            classPresent("com.sphereon.openid.fed.account.http.command.ListAccountsEndpointCommand")
    }

    private fun classPresent(name: String): Boolean =
        try {
            Class.forName(name)
            true
        } catch (_: ClassNotFoundException) {
            false
        } catch (_: LinkageError) {
            false
        }
}
