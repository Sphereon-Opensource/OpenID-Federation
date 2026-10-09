package com.sphereon.openid.fed.services.command.subordinateConstraint

import kotlinx.serialization.json.Json

/**
 * Storage encoding of subordinate constraints. Owned here rather than injected so the persisted form never depends on an
 * application's JSON configuration; absent members stay absent, since a stored `null` is not a valid constraint value
 * (OpenID Federation 1.1 §6.2).
 */
internal val SubordinateConstraintJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
