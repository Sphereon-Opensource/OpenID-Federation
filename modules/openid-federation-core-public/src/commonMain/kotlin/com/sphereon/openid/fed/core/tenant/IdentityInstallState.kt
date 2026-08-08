package com.sphereon.openid.fed.core.tenant

import kotlin.concurrent.Volatile

/**
 * Process-wide install/upgrade signals for [IdentityMode] default resolution.
 *
 * Persistence sets [isExistingDatabase] when a non-empty `schema_version` (or equivalent
 * pre-0.25 data) is detected **before** migrations run. That marks an **upgrade** from an
 * older open-source install (pre-mode / develop branch), which must default to
 * [IdentityMode.ACCOUNT].
 *
 * New empty databases leave this false → default may be [IdentityMode.EXTERNAL] unless
 * account modules are on the classpath (see [IdentityClasspath]).
 *
 * Explicit `oidf.identity.mode` / `OIDF_IDENTITY_MODE` always wins over this heuristic.
 */
object IdentityInstallState {
    /**
     * True when the process connected to a database that already had schema (upgrade path).
     */
    @Volatile
    var isExistingDatabase: Boolean = false
        private set

    /**
     * Call from persistence **before** applying migrations when [schemaVersion] &gt; 0
     * (or Account data already exists).
     */
    fun markExistingDatabase(schemaVersion: Long = 1L) {
        if (schemaVersion > 0L) {
            isExistingDatabase = true
        }
    }

    fun resetForTests() {
        isExistingDatabase = false
    }
}
