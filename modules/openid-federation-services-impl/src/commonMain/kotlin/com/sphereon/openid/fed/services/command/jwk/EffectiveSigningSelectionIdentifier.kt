package com.sphereon.openid.fed.services.command.jwk

import com.sphereon.openid.fed.common.Constants

/**
 * Resolve the identifier trusted by account signing-selection commands.
 *
 * The migration-seeded root Account intentionally has no persisted identifier. Only that exact
 * reserved account may use the configured federation root identifier. Other accounts require an
 * explicit persisted identifier, and a persisted value always remains authoritative.
 */
internal fun effectiveSigningSelectionIdentifier(
    username: String,
    persistedIdentifier: String?,
    configuredRootIdentifier: String,
): String? = persistedIdentifier ?: configuredRootIdentifier.takeIf {
    username == Constants.DEFAULT_ROOT_USERNAME && it.isNotBlank()
}
