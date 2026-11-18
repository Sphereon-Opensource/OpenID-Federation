package com.sphereon.openid.fed.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SchemaMigrationDescriptionsTest {
    @Test
    fun shouldDescribeEveryNumberedMigrationFileOfTheCurrentSchema() {
        for (file in 1L until Database.Schema.version) {
            val (filename, description) = describeMigration(file)
            assertEquals("$file.sqm", filename)
            assertNotEquals("Unknown migration", description, "$filename has no entry in MigrationDescriptions")
        }
    }

    @Test
    fun shouldFallBackToAnUnknownDescriptionForAFileBeyondTheSchema() {
        assertEquals("9999.sqm" to "Unknown migration", describeMigration(9999L))
    }
}
