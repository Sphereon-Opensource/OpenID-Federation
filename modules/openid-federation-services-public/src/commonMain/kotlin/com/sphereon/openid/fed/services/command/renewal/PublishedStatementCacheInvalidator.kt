package com.sphereon.openid.fed.services.command.renewal

/**
 * Drops the co-located federation resolver's cached copies of a statement this process just stored, so its own
 * resolution sees the republished statement instead of waiting for the cached one to expire. Resolvers in other
 * processes rely on the statement's `exp` bound and their key-mismatch refetch instead.
 */
interface PublishedStatementCacheInvalidator {
    /** [statementJwt] is the stored Entity Configuration or Subordinate Statement. Never fails the publish. */
    suspend fun published(statementJwt: String)
}
