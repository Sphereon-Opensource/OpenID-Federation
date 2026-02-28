package com.sphereon.openid.fed.services.command.criticalClaim

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

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
