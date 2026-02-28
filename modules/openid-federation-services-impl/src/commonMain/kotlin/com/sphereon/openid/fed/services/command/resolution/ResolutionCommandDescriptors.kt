package com.sphereon.openid.fed.services.command.resolution

import com.sphereon.core.api.service.RegistrableServiceCommandDescriptor
import com.sphereon.di.session.SessionScope
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(SessionScope::class)
interface ResolutionCommandDescriptors {

    @Provides @IntoSet
    fun resolveEntity(cmd: Lazy<ResolveEntityCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(ResolveEntityCommand.COMMAND_ID) { cmd.value }

    @Provides @IntoSet
    fun getSignedResolveResponseJwt(cmd: Lazy<GetSignedResolveResponseJwtCommand>): RegistrableServiceCommandDescriptor =
        RegistrableServiceCommandDescriptor.of(GetSignedResolveResponseJwtCommand.COMMAND_ID) { cmd.value }
}
