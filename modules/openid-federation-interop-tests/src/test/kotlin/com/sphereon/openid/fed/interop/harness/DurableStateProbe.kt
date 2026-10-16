package com.sphereon.openid.fed.interop.harness

import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.JSONObjectUtils
import org.testcontainers.containers.GenericContainer
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.EnumSet
import java.util.UUID

/** Safe, bounded evidence that the selected public key and its PKCS12 custody survived a JVM restart. */
internal object DurableStateProbe {
    private const val DATABASE = "openid-federation-db"
    private const val DATABASE_USER = "openid-federation-db-user"
    private const val MAX_KEYSTORE_SCAN_ENTRIES = 256
    private const val MAX_KEYSTORE_SCAN_DEPTH = 4
    private const val MAX_KEYSTORE_FILE_BYTES = 16L * 1024 * 1024
    private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val PRIVATE_JWK_FIELDS = setOf("d", "p", "q", "dp", "dq", "qi", "oth", "k")

    /** Contains only allowlisted persistence counts, revision, key ID and custody metadata. */
    internal data class Evidence(
        val rootAccountCount: Long,
        val selectedSigningRowCount: Long,
        val selectedJwkRowCount: Long,
        val revision: Long,
        val selectedKeyId: String,
        val privateKeyEntryCertificateMatched: Boolean,
        val keystoreType: String,
        val publicKeyThumbprint: String,
    )

    /**
     * Queries the fixed local PostgreSQL database inside the owned Testcontainer, then scans only
     * bounded PKCS12/PFX files beneath the supplied keystore root. Neither the query row nor JWK,
     * aliases, paths, passwords, or private key material are returned or included in diagnostics.
     */
    fun inspect(
        postgres: GenericContainer<*>,
        keystoreRootPath: Path,
        password: String,
        expectedSelectedKeyId: String,
        expectedPublicJwks: Map<String, Any?>,
    ): Evidence {
        require(password.isNotEmpty()) { "keystore password must not be empty" }
        val keyId = canonicalUuid(expectedSelectedKeyId)
        val databaseRow = readSelectedKeyRow(postgres, keyId)

        check(databaseRow.rootAccountCount == 1L) { "expected exactly one active root account row" }
        check(databaseRow.selectedSigningRowCount == 1L) { "expected exactly one selected signing-key row for the requested key" }
        check(databaseRow.selectedJwkRowCount == 1L) { "expected exactly one owned JWK row for the requested key" }
        check(databaseRow.selectedKeyId == keyId) { "persisted signing-key selection differs from the requested key" }
        check(databaseRow.revision > 0L) { "persisted signing-key revision must be positive" }

        val storedJwk = parsePublicJwk(databaseRow.jwkJson)
        check(storedJwk.keyID == databaseRow.kid) { "persisted JWK key ID differs from its database row" }
        val expectedKey = findExpectedJwk(expectedPublicJwks, databaseRow.kid)
        val storedPublicJwk = storedJwk.toPublicJWK() ?: error("persisted JWK has no public representation")
        val expectedPublicJwk = expectedKey.toPublicJWK() ?: error("advertised JWK has no public representation")
        val storedFields = storedPublicJwk.toJSONObject()
        val expectedFields = expectedPublicJwk.toJSONObject()
        val storedProjection = storedFields - "alg"
        val advertisedProjection = expectedFields - "alg"
        val storedOnlyFields = (storedProjection.keys - advertisedProjection.keys).sorted()
        val advertisedOnlyFields = (advertisedProjection.keys - storedProjection.keys).sorted()
        val changedFields = (storedProjection.keys intersect advertisedProjection.keys)
            .filter { storedProjection[it] != advertisedProjection[it] }
            .sorted()
        val storedAlgorithmMatchesColumn = storedFields["alg"] == null || storedFields["alg"] == databaseRow.alg
        val advertisedAlgorithmMatchesColumn = expectedFields["alg"] == databaseRow.alg
        val kidEqual = storedJwk.keyID == expectedKey.keyID && expectedKey.keyID == databaseRow.kid
        val thumbprintEqual = storedJwk.computeThumbprint() == expectedKey.computeThumbprint()
        val spkiEqual = MessageDigest.isEqual(publicKey(storedJwk), publicKey(expectedKey))
        check(
            storedAlgorithmMatchesColumn && advertisedAlgorithmMatchesColumn &&
                storedProjection == advertisedProjection && kidEqual && thumbprintEqual && spkiEqual,
        ) {
            "persisted and advertised public JWKs are not the same selected key " +
                "(storedOnlyFields=$storedOnlyFields, advertisedOnlyFields=$advertisedOnlyFields, " +
                "changedFields=$changedFields, storedAlgorithmMatchesColumn=$storedAlgorithmMatchesColumn, " +
                "advertisedAlgorithmMatchesColumn=$advertisedAlgorithmMatchesColumn, kidEqual=$kidEqual, " +
                "thumbprintEqual=$thumbprintEqual, spkiEqual=$spkiEqual)"
        }
        val publicKey = publicKey(storedJwk)
        val thumbprint = storedJwk.computeThumbprint().toString()
        val custody = inspectPkcs12(keystoreRootPath, password, publicKey)

        return Evidence(
            rootAccountCount = databaseRow.rootAccountCount,
            selectedSigningRowCount = databaseRow.selectedSigningRowCount,
            selectedJwkRowCount = databaseRow.selectedJwkRowCount,
            revision = databaseRow.revision,
            selectedKeyId = keyId,
            privateKeyEntryCertificateMatched = custody.matched,
            keystoreType = custody.type,
            publicKeyThumbprint = thumbprint,
        )
    }

    private data class SelectedKeyRow(
        val rootAccountCount: Long,
        val selectedSigningRowCount: Long,
        val selectedJwkRowCount: Long,
        val revision: Long,
        val selectedKeyId: String,
        val kid: String,
        val alg: String,
        val jwkJson: String,
    )

    private data class KeystoreEvidence(val matched: Boolean, val type: String)

    private fun readSelectedKeyRow(postgres: GenericContainer<*>, keyId: String): SelectedKeyRow {
        // keyId has already passed strict UUID validation; fixed identifiers match InteropEnvironment.
        val sql = """
            SELECT json_build_object(
                'rootAccountCount', (SELECT count(*) FROM Account WHERE username = 'root' AND deleted_at IS NULL),
                'selectedSigningRowCount', (
                    SELECT count(*) FROM AccountSigningKey signing
                    JOIN Account account ON account.id = signing.account_id
                    WHERE account.username = 'root' AND account.deleted_at IS NULL
                      AND signing.jwk_id = '$keyId'::uuid
                ),
                'selectedJwkRowCount', (
                    SELECT count(*) FROM Jwk jwk
                    JOIN Account account ON account.id = jwk.account_id
                    WHERE account.username = 'root' AND account.deleted_at IS NULL
                      AND jwk.id = '$keyId'::uuid
                ),
                'revision', (
                    SELECT signing.revision FROM AccountSigningKey signing
                    JOIN Account account ON account.id = signing.account_id
                    WHERE account.username = 'root' AND account.deleted_at IS NULL
                      AND signing.jwk_id = '$keyId'::uuid
                    LIMIT 1
                ),
                'selectedKeyId', (
                    SELECT signing.jwk_id::text FROM AccountSigningKey signing
                    JOIN Account account ON account.id = signing.account_id
                    WHERE account.username = 'root' AND account.deleted_at IS NULL
                      AND signing.jwk_id = '$keyId'::uuid
                    LIMIT 1
                ),
                'kid', (
                    SELECT jwk.kid FROM Jwk jwk
                    JOIN Account account ON account.id = jwk.account_id
                    WHERE account.username = 'root' AND account.deleted_at IS NULL
                      AND jwk.id = '$keyId'::uuid
                    LIMIT 1
                ),
                'alg', (
                    SELECT jwk.alg FROM Jwk jwk
                    JOIN Account account ON account.id = jwk.account_id
                    WHERE account.username = 'root' AND account.deleted_at IS NULL
                      AND jwk.id = '$keyId'::uuid
                    LIMIT 1
                ),
                'jwkJson', (
                    SELECT jwk.key FROM Jwk jwk
                    JOIN Account account ON account.id = jwk.account_id
                    WHERE account.username = 'root' AND account.deleted_at IS NULL
                      AND jwk.id = '$keyId'::uuid
                    LIMIT 1
                )
            )::text;
        """.trimIndent()
        val result = postgres.execInContainer(
            "psql", "-X", "-q", "-A", "-t", "-U", DATABASE_USER, "-d", DATABASE, "-c", sql,
        )
        check(result.exitCode == 0) { "bounded PostgreSQL durability query failed (psql exit ${result.exitCode})" }
        val json = result.stdout.trim()
        check(json.isNotEmpty() && '\n' !in json) { "PostgreSQL durability query returned an unexpected result shape" }
        val row = try {
            JSONObjectUtils.parse(json)
        } catch (_: Exception) {
            error("PostgreSQL durability query returned invalid JSON")
        }
        return try {
            SelectedKeyRow(
                rootAccountCount = (row["rootAccountCount"] as Number).toLong(),
                selectedSigningRowCount = (row["selectedSigningRowCount"] as Number).toLong(),
                selectedJwkRowCount = (row["selectedJwkRowCount"] as Number).toLong(),
                revision = (row["revision"] as Number).toLong(),
                selectedKeyId = row["selectedKeyId"] as String,
                kid = row["kid"] as String,
                alg = row["alg"] as String,
                jwkJson = row["jwkJson"] as String,
            )
        } catch (_: Exception) {
            error("PostgreSQL durability query omitted required selected-key fields")
        }
    }

    private fun inspectPkcs12(rootPath: Path, password: String, expectedPublicKey: ByteArray): KeystoreEvidence {
        require(!Files.isSymbolicLink(rootPath)) { "keystore root must not be a symbolic link" }
        require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) { "keystore root is not a directory" }
        val root = rootPath.toRealPath(LinkOption.NOFOLLOW_LINKS)
        var visitedEntries = 0
        val candidates = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java),
            MAX_KEYSTORE_SCAN_DEPTH,
            object : SimpleFileVisitor<Path>() {
                private fun countEntry() {
                    visitedEntries += 1
                    check(visitedEntries <= MAX_KEYSTORE_SCAN_ENTRIES) { "keystore scan exceeded its entry limit" }
                }

                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    countEntry()
                    check(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "keystore scan encountered a symbolic link" }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    countEntry()
                    check(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) { "keystore scan encountered a symbolic link" }
                    if (attrs.isDirectory) return FileVisitResult.CONTINUE
                    if (attrs.isRegularFile && file.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("p12", "pfx")) {
                        check(attrs.size() in 1..MAX_KEYSTORE_FILE_BYTES) { "keystore file size is outside the allowed bound" }
                        val candidate = file.toRealPath(LinkOption.NOFOLLOW_LINKS)
                        check(candidate.startsWith(root)) { "keystore scan escaped its root" }
                        candidates.add(candidate)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    error("keystore scan could not inspect an entry")
                }
            },
        )
        check(candidates.isNotEmpty()) { "keystore scan found no bounded PKCS12/PFX files" }

        val passwordChars = password.toCharArray()
        try {
            var matches = 0
            candidates.forEach { file ->
                val store = KeyStore.getInstance("PKCS12")
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { store.load(it, passwordChars) }
                val aliases = store.aliases()
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    if (!store.isKeyEntry(alias)) continue
                    val certificate = store.getCertificate(alias) as? X509Certificate ?: continue
                    if (MessageDigest.isEqual(certificate.publicKey.encoded, expectedPublicKey)) matches += 1
                }
            }
            check(matches == 1) { "expected exactly one PKCS12 private-key-entry certificate matching the selected public JWK" }
        } finally {
            passwordChars.fill('\u0000')
        }
        return KeystoreEvidence(matched = true, type = "PKCS12")
    }

    private fun findExpectedJwk(expectedPublicJwks: Map<String, Any?>, kid: String): JWK {
        val keys = expectedPublicJwks["keys"] as? List<*> ?: error("expected public JWKS has no keys array")
        val matching = keys.mapNotNull { raw ->
            val value = raw as? Map<*, *> ?: return@mapNotNull null
            val objectValue = value.entries.associate { (name, entry) -> name.toString() to entry }
            try {
                JWK.parse(objectValue)
            } catch (_: Exception) {
                error("expected public JWKS contains an invalid JWK")
            }
        }.filter { it.keyID == kid }
        check(matching.size == 1) { "expected public JWKS must contain exactly one key matching the selected KID" }
        return matching.single()
    }

    private fun parsePublicJwk(json: String): JWK {
        val parsed = try {
            JSONObjectUtils.parse(json)
        } catch (_: Exception) {
            error("persisted JWK is not valid JSON")
        }
        check(parsed.keys.none { it.lowercase() in PRIVATE_JWK_FIELDS }) { "persisted JWK contains private or symmetric key material" }
        return try {
            JWK.parse(parsed).also { check(it.toPublicJWK() != null) { "persisted JWK has no public representation" } }
        } catch (_: Exception) {
            error("persisted public JWK could not be parsed")
        }
    }

    private fun publicKey(jwk: JWK): ByteArray = when (val publicJwk = jwk.toPublicJWK()) {
        is ECKey -> publicJwk.toECPublicKey().encoded
        is RSAKey -> publicJwk.toRSAPublicKey().encoded
        else -> error("selected public JWK type is unsupported for PKCS12 certificate comparison")
    }

    private fun canonicalUuid(value: String): String {
        require(UUID_TEXT.matches(value)) { "selected key ID must be a UUID" }
        val uuid = UUID.fromString(value)
        require(uuid.toString().equals(value, ignoreCase = true)) { "selected key ID must use canonical UUID form" }
        return uuid.toString()
    }
}
