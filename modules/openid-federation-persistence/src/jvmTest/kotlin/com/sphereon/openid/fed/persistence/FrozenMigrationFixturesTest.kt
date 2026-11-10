package com.sphereon.openid.fed.persistence

import org.junit.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The frozen source migrations are evidence of what releases shipped, so an edit to one must fail loudly. */
class FrozenMigrationFixturesTest {
    private val declaredFile = Regex("\"file\":\\s*\"([^\"]+)\"[^}]*?\"sha256\":\\s*\"([0-9a-f]{64})\"")

    @Test
    fun shouldMatchTheDigestsRecordedWhenTheMigrationsWereFrozen() {
        val declared = declaredFile.findAll(UpgradeFixtures.resource("frozen/provenance.json")).toList()
        assertEquals(17, declared.size, "provenance.json must declare 1.sqm to 16.sqm of 0.23.0 and the 2.sqm of 0.20.7")
        for (match in declared) {
            val (file, recorded) = match.destructured
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(UpgradeFixtures.resource("frozen/$file").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(recorded, digest, "frozen/$file no longer matches the bytes the release shipped")
        }
    }

    @Test
    fun shouldDifferBetweenTheTwoSchemaClassesOnlyByTheJwkAlgColumn() {
        val classA = SourceSchema.CLASS_A.migration(2).lines().map(String::trim).filter(String::isNotEmpty)
        val classB = SourceSchema.CLASS_B.migration(2).lines().map(String::trim).filter(String::isNotEmpty)
        assertEquals(listOf("alg VARCHAR(255),"), classA - classB.toSet())
        assertTrue((classB - classA.toSet()).isEmpty(), "schema class B must not define anything schema class A lacks")
    }
}
