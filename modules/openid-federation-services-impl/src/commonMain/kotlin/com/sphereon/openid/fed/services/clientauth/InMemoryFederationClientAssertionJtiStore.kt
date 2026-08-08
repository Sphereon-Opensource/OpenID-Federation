package com.sphereon.openid.fed.services.clientauth

import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.time.Clock

/**
 * Session-scoped in-memory jti replay guard for federation endpoint client assertions.
 * Same role as IDK InMemoryClientAssertionJtiStore for single-node deployments.
 */
@Inject
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class, binding = binding<FederationClientAssertionJtiStore>())
class InMemoryFederationClientAssertionJtiStore : FederationClientAssertionJtiStore {
    private val used = LinkedHashMap<String, Long>()
    private val lock = Any()

    override suspend fun recordIfNew(
        clientEntityId: String,
        jti: String,
        expiresAtEpochSeconds: Long,
    ): Boolean = synchronized(lock) {
        val now = Clock.System.now().epochSeconds
        val expired = used.filterValues { it <= now }.keys.toList()
        expired.forEach { used.remove(it) }
        val key = "$clientEntityId|$jti"
        if (used.containsKey(key)) return@synchronized false
        used[key] = expiresAtEpochSeconds
        while (used.size > MAX_ENTRIES) {
            val oldest = used.keys.firstOrNull() ?: break
            used.remove(oldest)
        }
        true
    }

    companion object {
        private const val MAX_ENTRIES = 10_000
    }
}
