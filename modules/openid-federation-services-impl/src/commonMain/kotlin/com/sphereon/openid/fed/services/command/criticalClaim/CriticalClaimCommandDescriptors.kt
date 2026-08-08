package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.ContributesTo

@ContributesTo(SessionScope::class)
interface CriticalClaimCommandDescriptors {

    @Provides @IntoSet
    fun createCriticalClaim(cmd: Lazy<CreateCriticalClaimCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(CreateCriticalClaimCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun deleteCriticalClaim(cmd: Lazy<DeleteCriticalClaimCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(DeleteCriticalClaimCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun findCriticalClaimsByAccount(cmd: Lazy<FindCriticalClaimsByAccountCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(FindCriticalClaimsByAccountCommand.COMMAND_ID) { cmd.value }
}
