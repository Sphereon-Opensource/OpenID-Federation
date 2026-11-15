package com.sphereon.openid.fed.persistence

/**
 * Opens the persistence layer now, which migrates or verifies the schema according to `oidf.datasource.schema.management`.
 *
 * [Persistence] otherwise initializes on its first query, so a server that never asked at startup came up healthy over a
 * schema it could not use, and every later query failed with `NoClassDefFoundError` because a failed object initializer
 * cannot be retried. A server calls this before it binds its port and stops when it throws.
 *
 * @throws SchemaMigrationException or the other cause of the failed initialization, never the wrapping error. That holds for the
 * first call only: Kotlin cannot retry a failed object initializer, so a second call fails with `NoClassDefFoundError`. A caller
 * stops the process on the first failure.
 */
fun initializePersistence() {
    try {
        Persistence.accountQueries
    } catch (failure: ExceptionInInitializerError) {
        throw failure.cause ?: failure
    }
}
