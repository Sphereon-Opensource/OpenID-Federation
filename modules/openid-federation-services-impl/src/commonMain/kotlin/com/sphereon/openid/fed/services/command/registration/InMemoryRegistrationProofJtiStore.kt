package com.sphereon.openid.fed.services.command.registration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.time.Clock

/**
 * Single-node replay guard for Automatic Registration proofs, shared by every request of the application. Hosts with
 * more than one node replace this binding with a shared store.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<RegistrationProofJtiStore>())
class InMemoryRegistrationProofJtiStore : RegistrationProofJtiStore {
    private val used = LinkedHashMap<String, Long>()
    private val lock = Any()

    override suspend fun recordIfNew(clientId: String, jti: String, expiresAtEpochSeconds: Long): Boolean = synchronized(lock) {
        val now = Clock.System.now().epochSeconds
        used.entries.removeAll { it.value <= now }
        val key = "$clientId|$jti"
        if (key in used) return@synchronized false
        if (used.size >= MAX_ENTRIES) return@synchronized false
        used[key] = expiresAtEpochSeconds
        true
    }

    private companion object {
        const val MAX_ENTRIES = 100_000
    }
}
