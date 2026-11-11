package com.sphereon.openid.fed.persistence

/** File name and description recorded in `schema_migration_history` for each migration file. */
private val MIGRATION_DESCRIPTIONS = mapOf(
    1L to ("1.sqm" to "Create Account table with username, identifier, timestamps, and soft-delete"),
    2L to ("2.sqm" to "Create Jwk table for storing JSON Web Keys with KMS references"),
    3L to ("3.sqm" to "Create Subordinate table for managing subordinate entities"),
    4L to ("4.sqm" to "Create EntityConfigurationStatement table for signed entity config JWTs"),
    5L to ("5.sqm" to "Create Metadata table for storing account-level metadata"),
    6L to ("6.sqm" to "Create AuthorityHint table for authority identifiers"),
    7L to ("7.sqm" to "Create Crit table for critical claims"),
    8L to ("8.sqm" to "Create SubordinateStatement table for signed statements about subordinates"),
    9L to ("9.sqm" to "Create SubordinateJwk table for subordinate entity JWKs"),
    10L to ("10.sqm" to "Create SubordinateMetadata table with unique constraints"),
    11L to ("11.sqm" to "Create TrustMarkType table for trust mark type definitions"),
    12L to ("12.sqm" to "Create TrustMarkIssuer table for authorized issuers"),
    13L to ("13.sqm" to "Create TrustMark table for issued trust marks with expiration"),
    14L to ("14.sqm" to "Create ReceivedTrustMark table for received trust mark JWTs"),
    15L to ("15.sqm" to "Create Log table for audit logging with severity tracking"),
    16L to ("16.sqm" to "Create MetadataPolicy table for JSON policy documents"),
    17L to ("17.sqm" to "Alter Account table to add tenant_source column"),
    18L to ("18.sqm" to "Create TrustAnchorHint table for trust anchor hint identifiers"),
    19L to ("19.sqm" to "Create SubordinateConstraint table for subordinate entity constraints"),
    20L to ("20.sqm" to "Create ForeignSubordinateStatement table for received Immediate Superior JWTs"),
    21L to ("21.sqm" to "Create account-selected signing key binding with revision tracking"),
    22L to ("22.sqm" to "Create TrustMarkOwner table and the issuer's Trust Mark delegation per type"),
    23L to ("23.sqm" to "Separate historical key expiry, revocation reason tokens and operator descriptions"),
    24L to ("24.sqm" to "Persist account-role metadata-policy revision and operation fences"),
    25L to ("25.sqm" to "Record the last signing-key operation generation, token and request snapshot per account"),
    26L to ("26.sqm" to "Add versioned Trust Mark type definitions and their revision history"),
    27L to ("27.sqm" to "Administration document revisions, atomic publication artifacts and scheduled-key queue"),
    28L to ("28.sqm" to "Allow distinct logical JOSE keys to share an authorized physical key during rollover"),
    29L to ("29.sqm" to "Fill empty Jwk.alg from the public key and lower-case the legacy KMS provider ids"),
)

internal fun describeMigration(file: Long): Pair<String, String> = MIGRATION_DESCRIPTIONS[file] ?: ("$file.sqm" to "Unknown migration")
