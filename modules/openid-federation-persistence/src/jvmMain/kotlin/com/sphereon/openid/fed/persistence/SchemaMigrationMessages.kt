package com.sphereon.openid.fed.persistence

internal const val UPGRADE_POINTER = "See docs/UPGRADE.md."

internal fun newerThanThisBuild(current: Long, targetVersion: Long) =
    "The database is at schema version $current, but this build needs version $targetVersion. A newer release migrated it, " +
        "and running an older release against a newer schema is not supported. Start the release that migrated it, " +
        "or restore a backup taken before that upgrade. $UPGRADE_POINTER"

internal fun tablesWithoutRecordedVersion() =
    "The database has tables but no recorded schema version, so it was not created or upgraded by a supported release " +
        "and its state cannot be verified. Nothing was changed. Restore a backup, or if you know the schema version it holds, " +
        "record it with INSERT INTO schema_version (version) VALUES (<version>) and start the service again. $UPGRADE_POINTER"

internal fun commitOutcomeUnknown(from: Long?, targetVersion: Long, cause: Throwable): String =
    "Schema migration from version ${from ?: "unknown"} to $targetVersion ran to the end, but the database did not confirm the commit " +
        "(${generateSequence(cause) { it.cause }.last().message ?: cause}). The migration may or may not have been committed. " +
        "Do not restore yet: start the service again, it either finds the schema at version $targetVersion or applies the migration again. $UPGRADE_POINTER"

internal fun lockWaitGaveUp(seconds: Long, state: String) =
    "Schema migration gave up after waiting $seconds seconds for a lock. Another instance is still " +
        "migrating this database, or a session holds a conflicting lock on one of its tables. The migration was rolled " +
        "back and the database is $state. Start the service again once the other work has finished. $UPGRADE_POINTER"

internal fun migrationFailed(from: Long?, targetVersion: Long, where: String, reason: String, state: String) =
    "Schema migration from version ${from ?: "unknown"} to $targetVersion failed while $where: " +
        "$reason. The migration runs in one transaction that has been rolled back, so the database " +
        "is $state. Fix the cause and start the service again, or restore the backup taken before the upgrade. $UPGRADE_POINTER"
