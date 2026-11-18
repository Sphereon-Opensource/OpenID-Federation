package com.sphereon.openid.fed.persistence

/**
 * Opens the persistence layer now, which migrates or verifies the schema according to `oidf.datasource.schema.management`.
 *
 * [Persistence] otherwise opens the database on its first query, so a server that never asked at startup came up healthy
 * over a schema it could not use. A server calls this before it binds its port and stops when it throws.
 *
 * @throws SchemaMigrationException or the other cause of the failed initialization, never a wrapping error. [Persistence]
 * opens lazily and keeps no failed state, so a later call tries again; a server still stops on the first failure.
 */
fun initializePersistence() {
    try {
        Persistence.accountQueries
    } catch (failure: ExceptionInInitializerError) {
        throw failure.cause ?: failure
    }
}
